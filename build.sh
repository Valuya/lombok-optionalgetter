#!/usr/bin/env bash
# Build an "overlay" lombok jar: the official published lombok, plus the
# @OptionalGetter annotation and its handlers injected as lombok shadow entries.
#
# Why an overlay and not a companion jar: lombok's ShadowClassLoader only loads
# annotation handlers from lombok's OWN jar (an inOwnBase filter), so a handler
# shipped in a separate sibling jar is silently ignored no matter how it's
# packaged. Injecting into a copy of the official jar is the lightest thing that
# actually works, and it tracks upstream by simply pointing at a newer version.
#
# Usage: ./build.sh [LOMBOK_VERSION]     (default: value of LOMBOK_VERSION env or 1.18.46)
set -euo pipefail
cd "$(dirname "$0")"

LOMBOK_VERSION="${1:-${LOMBOK_VERSION:-1.18.46}}"
OUT_DIR="target"
WORK="$OUT_DIR/work"
CLASSES="$OUT_DIR/classes"
DESHADOW="$OUT_DIR/lombok-deshadow.jar"
OUT_JAR="$OUT_DIR/lombok-${LOMBOK_VERSION}.jar"

JAVA_EXPORTS=(
  --add-exports jdk.compiler/com.sun.tools.javac.code=ALL-UNNAMED
  --add-exports jdk.compiler/com.sun.tools.javac.tree=ALL-UNNAMED
  --add-exports jdk.compiler/com.sun.tools.javac.util=ALL-UNNAMED
  --add-exports jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED
  --add-exports jdk.compiler/com.sun.tools.javac.processing=ALL-UNNAMED
)

rm -rf "$OUT_DIR"; mkdir -p "$WORK" "$CLASSES"

# 1. Resolve the official lombok jar (local ~/.m2, else Maven Central).
STOCK="$HOME/.m2/repository/org/projectlombok/lombok/${LOMBOK_VERSION}/lombok-${LOMBOK_VERSION}.jar"
if [ ! -f "$STOCK" ]; then
  STOCK="$OUT_DIR/lombok-stock.jar"
  echo "Downloading lombok ${LOMBOK_VERSION} from Maven Central..."
  curl -fsSL -o "$STOCK" \
    "https://repo1.maven.org/maven2/org/projectlombok/lombok/${LOMBOK_VERSION}/lombok-${LOMBOK_VERSION}.jar"
fi
echo "Stock lombok: $STOCK"

# 2. Deshadow it into a compile-only classpath (.SCL.lombok -> .class).
DS="$OUT_DIR/deshadow"; mkdir -p "$DS"
( cd "$DS" && unzip -q "$STOCK" )
if [ -d "$DS/SCL.lombok" ]; then
  ( cd "$DS/SCL.lombok" && find . -name '*.SCL.lombok' | while read -r f; do
      t="../${f%.SCL.lombok}.class"; mkdir -p "$(dirname "$t")"; cp "$f" "$t"; done )
  rm -rf "$DS/SCL.lombok"
fi
( cd "$DS" && jar cf "../$(basename "$DESHADOW")" . )

# 3. Locate an ecj jar (needed only for the Eclipse handler). Optional.
ECJ_JAR="${ECJ_JAR:-}"
if [ -z "$ECJ_JAR" ]; then
  ECJ_JAR="$(find deps -iname 'ecj*.jar' 2>/dev/null | head -1 || true)"
fi
CP="$DESHADOW"; [ -n "$ECJ_JAR" ] && CP="$DESHADOW:$ECJ_JAR"

# 4. Compile the annotation + javac handler (mandatory — this is what a Maven/javac build uses).
echo "Compiling annotation + javac handler"
javac "${JAVA_EXPORTS[@]}" -cp "$CP" -d "$CLASSES" \
  src/main/java/lombok/OptionalGetter.java \
  src/main/java/lombok/javac/handlers/HandleOptionalGetter.java

# 4b. Eclipse handler is best-effort: it only matters for the Eclipse IDE, and it compiles against ecj's
# internal AST API, which drifts between ecj versions. If no ecj is found, or it fails to compile, warn and
# ship a javac-only overlay rather than failing the whole build.
ECLIPSE_OK=0
if [ -n "$ECJ_JAR" ]; then
  if javac "${JAVA_EXPORTS[@]}" -cp "$CP:$CLASSES" -d "$CLASSES" \
       src/main/java/lombok/eclipse/handlers/HandleOptionalGetter.java 2>"$OUT_DIR/eclipse-compile.log"; then
    ECLIPSE_OK=1
    echo "Compiled eclipse handler (ecj: $ECJ_JAR)"
  else
    echo "WARNING: eclipse handler failed to compile against $ECJ_JAR; shipping javac-only overlay."
    echo "         (see $OUT_DIR/eclipse-compile.log — usually an ecj-version API mismatch)"
  fi
else
  echo "No ecj found (deps/ecj*.jar or \$ECJ_JAR); shipping javac-only overlay."
fi

# 5. Overlay: copy the official jar, inject our classes, register the handlers.
( cd "$WORK" && unzip -q "$STOCK" )
inject_scl() { # <class-relpath-without-.class>
  local rel="$1"
  cp "$CLASSES/${rel}.class" "$WORK/SCL.lombok/${rel}.SCL.lombok"
}
# annotation: real public class (like lombok.Getter) + shadow copy (loaded via SCL at processing time)
cp "$CLASSES/lombok/OptionalGetter.class" "$WORK/lombok/OptionalGetter.class"
cp "$CLASSES/lombok/OptionalGetter\$AnyAnnotation.class" "$WORK/lombok/OptionalGetter\$AnyAnnotation.class"
inject_scl 'lombok/OptionalGetter'
inject_scl 'lombok/OptionalGetter$AnyAnnotation'
# javac handler + inner classes
for c in "$CLASSES"/lombok/javac/handlers/HandleOptionalGetter*.class; do
  rel="${c#$CLASSES/}"; rel="${rel%.class}"; inject_scl "$rel"
done
append_handler() { # <service-file-relpath> <handler-fqcn>
  local svc="$WORK/$1" fqcn="$2"
  [ -f "$svc" ] || { echo "missing service file: $1" >&2; exit 1; }
  # ensure the file ends with a newline before appending (stock files may not)
  [ -n "$(tail -c1 "$svc")" ] && printf '\n' >> "$svc"
  printf '%s\n' "$fqcn" >> "$svc"
}
append_handler 'META-INF/services/lombok.javac.JavacAnnotationHandler' 'lombok.javac.handlers.HandleOptionalGetter'
# eclipse handler, only if it was compiled
if [ "$ECLIPSE_OK" = "1" ]; then
  for c in "$CLASSES"/lombok/eclipse/handlers/HandleOptionalGetter*.class; do
    rel="${c#$CLASSES/}"; rel="${rel%.class}"; inject_scl "$rel"
  done
  append_handler 'META-INF/services/lombok.eclipse.EclipseAnnotationHandler' 'lombok.eclipse.handlers.HandleOptionalGetter'
fi
( cd "$WORK" && jar cf "../../$OUT_JAR" . )
echo "Built: $OUT_JAR"

# 6. Smoke test: compile+run a class using @OptionalGetter through the overlay jar.
ST="$OUT_DIR/smoke"; mkdir -p "$ST"
cat > "$ST/Person.java" <<'JAVA'
public class Person {
  @lombok.OptionalGetter private String name;
  @lombok.OptionalGetter private int age;
  @lombok.Getter private String plain;
  Person(String n, int a, String p) { this.name = n; this.age = a; this.plain = p; }
  public static void main(String[] x) {
    Person p = new Person(null, 42, "z");
    java.util.Optional<String> n = p.getName();   // static-type assertion
    if (!p.getName().equals(java.util.Optional.empty())) throw new AssertionError("getName");
    if (!p.getAge().equals(java.util.Optional.of(42))) throw new AssertionError("getAge");
    if (!p.getPlain().equals("z")) throw new AssertionError("getPlain");
    System.out.println("SMOKE OK: name=" + p.getName() + " age=" + p.getAge() + " plain=" + p.getPlain());
  }
}
JAVA
javac -proc:full -cp "$OUT_JAR" -d "$ST" "$ST/Person.java"
java -cp "$ST:$OUT_JAR" Person
