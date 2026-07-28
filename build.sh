#!/usr/bin/env bash
# Build an "overlay" lombok jar: the official published lombok, plus the
# @OptionalGetter annotation and its handlers injected as lombok shadow entries.
#
# Why an overlay and not a companion jar: lombok's ShadowClassLoader loads
# annotation handlers only from lombok's OWN jar (an inOwnBase filter), so a
# handler shipped in a separate sibling jar is silently ignored no matter how
# it's packaged. Injecting into a copy of the official jar is the lightest thing
# that actually works, and it tracks upstream by just bumping lombok.version.
#
# lombok (and ecj, for the optional Eclipse handler) are resolved as Maven
# dependencies from pom.xml — no download URLs live here. The version is read
# from the pom's lombok.version property.
set -euo pipefail
cd "$(dirname "$0")"

MVN="${MVN:-mvn}"
# LOMBOK_VERSION = the upstream lombok we overlay; RELEASE_VERSION = the version we publish (its own lane).
LOMBOK_VERSION="${1:-${LOMBOK_VERSION:-$("$MVN" -q -DforceStdout help:evaluate -Dexpression=lombok.version)}}"
RELEASE_VERSION="${RELEASE_VERSION:-$("$MVN" -q -DforceStdout help:evaluate -Dexpression=revision)}"
OUT_DIR="target"
DEPS="$OUT_DIR/deps"
WORK="$OUT_DIR/work"
CLASSES="$OUT_DIR/classes"
DESHADOW="$OUT_DIR/lombok-deshadow.jar"
OUT_JAR="$OUT_DIR/lombok-${RELEASE_VERSION}.jar"

JAVA_EXPORTS=(
  --add-exports jdk.compiler/com.sun.tools.javac.code=ALL-UNNAMED
  --add-exports jdk.compiler/com.sun.tools.javac.tree=ALL-UNNAMED
  --add-exports jdk.compiler/com.sun.tools.javac.util=ALL-UNNAMED
  --add-exports jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED
  --add-exports jdk.compiler/com.sun.tools.javac.processing=ALL-UNNAMED
)

rm -rf "$OUT_DIR"; mkdir -p "$WORK" "$CLASSES" "$DEPS"

# 1. Resolve lombok + ecj into target/deps via Maven (respects pom.xml versions).
echo "Overlaying lombok ${LOMBOK_VERSION}  ->  publishing as ${RELEASE_VERSION}"
echo "Resolving dependencies via Maven..."
"$MVN" -q -Dlombok.version="$LOMBOK_VERSION" \
  -DoutputDirectory="$PWD/$DEPS" -DincludeScope=provided \
  dependency:copy-dependencies
# absolute paths: these are used inside `cd` subshells below
STOCK="$(ls "$PWD/$DEPS"/lombok-*.jar 2>/dev/null | head -1)"
ECJ_JAR="$(ls "$PWD/$DEPS"/ecj-*.jar "$PWD/$DEPS"/org.eclipse.jdt.core*.jar 2>/dev/null | head -1 || true)"
[ -n "$STOCK" ] || { echo "could not resolve lombok jar into $DEPS" >&2; exit 1; }
echo "Stock lombok: $STOCK"

# 2. Deshadow it into a compile-only classpath (.SCL.lombok -> .class). The published jar hides
#    lombok's internal handler API as .SCL.lombok, so an extension can't compile against it directly.
DS="$OUT_DIR/deshadow"; mkdir -p "$DS"
( cd "$DS" && unzip -q "$STOCK" )
if [ -d "$DS/SCL.lombok" ]; then
  ( cd "$DS/SCL.lombok" && find . -name '*.SCL.lombok' | while read -r f; do
      t="../${f%.SCL.lombok}.class"; mkdir -p "$(dirname "$t")"; cp "$f" "$t"; done )
  rm -rf "$DS/SCL.lombok"
fi
( cd "$DS" && jar cf "../$(basename "$DESHADOW")" . )

# 3. Compile the annotation + config keys + javac handler (mandatory — this is what a Maven/javac build uses).
CP="$DESHADOW"; [ -n "$ECJ_JAR" ] && CP="$DESHADOW:$ECJ_JAR"
echo "Compiling annotation + config keys + javac handler"
javac "${JAVA_EXPORTS[@]}" -cp "$CP" -d "$CLASSES" \
  src/main/java/lombok/OptionalGetter.java \
  src/main/java/lombok/OptionalGetterConfigurationKeys.java \
  src/main/java/lombok/javac/handlers/HandleOptionalGetter.java

# 3b. Eclipse handler is best-effort: it only matters for the Eclipse IDE, and it compiles against ecj's
#     internal AST API, which drifts between ecj versions. If ecj is missing or it fails to compile, warn
#     and ship a javac-only overlay rather than failing the whole build.
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
  echo "No ecj resolved; shipping javac-only overlay."
fi

# 4. Overlay: copy the official jar, inject our classes, register the handlers.
( cd "$WORK" && unzip -q "$STOCK" )
inject_scl() { # <class-relpath-without-.class>
  cp "$CLASSES/$1.class" "$WORK/SCL.lombok/$1.SCL.lombok"
}
# annotation: real public class (like lombok.Getter) + shadow copy (loaded via SCL at processing time)
cp "$CLASSES/lombok/OptionalGetter.class" "$WORK/lombok/OptionalGetter.class"
cp "$CLASSES/lombok/OptionalGetter\$AnyAnnotation.class" "$WORK/lombok/OptionalGetter\$AnyAnnotation.class"
inject_scl 'lombok/OptionalGetter'
inject_scl 'lombok/OptionalGetter$AnyAnnotation'
# config-keys registry + its anonymous ConfigurationKey subclass ($1), loaded via SCL at processing time,
# and its ConfigurationKeysLoader SPI file
for c in "$CLASSES"/lombok/OptionalGetterConfigurationKeys*.class; do
  rel="${c#$CLASSES/}"; inject_scl "${rel%.class}"
done
mkdir -p "$WORK/META-INF/services"
echo 'lombok.OptionalGetterConfigurationKeys' > "$WORK/META-INF/services/lombok.core.configuration.ConfigurationKeysLoader"
for c in "$CLASSES"/lombok/javac/handlers/HandleOptionalGetter*.class; do
  rel="${c#$CLASSES/}"; inject_scl "${rel%.class}"
done
append_handler() { # <service-file-relpath> <handler-fqcn>
  local svc="$WORK/$1"
  [ -f "$svc" ] || { echo "missing service file: $1" >&2; exit 1; }
  [ -n "$(tail -c1 "$svc")" ] && printf '\n' >> "$svc"   # stock files may lack a trailing newline
  printf '%s\n' "$2" >> "$svc"
}
append_handler 'META-INF/services/lombok.javac.JavacAnnotationHandler' 'lombok.javac.handlers.HandleOptionalGetter'
if [ "$ECLIPSE_OK" = "1" ]; then
  for c in "$CLASSES"/lombok/eclipse/handlers/HandleOptionalGetter*.class; do
    rel="${c#$CLASSES/}"; inject_scl "${rel%.class}"
  done
  append_handler 'META-INF/services/lombok.eclipse.EclipseAnnotationHandler' 'lombok.eclipse.handlers.HandleOptionalGetter'
fi
( cd "$WORK" && jar cf "../../$OUT_JAR" . )
echo "Built: $OUT_JAR"

# 5. Smoke test through the overlay jar (javac -proc:full — the same path a real build uses).
ST="$OUT_DIR/smoke"; mkdir -p "$ST"
# 5a. Positive: @OptionalGetter on reference fields yields Optional accessors named getFooOptional() by
#     default (lombok.optionalGetter.suffix); stock @Getter still works.
cat > "$ST/Person.java" <<'JAVA'
public class Person {
  @lombok.OptionalGetter private String name;
  @lombok.OptionalGetter private Integer age;   // boxed reference type is fine
  @lombok.Getter private int rank;               // primitives use plain @Getter
  Person(String n, Integer a, int r) { this.name = n; this.age = a; this.rank = r; }
  public static void main(String[] x) {
    Person p = new Person(null, 42, 7);
    java.util.Optional<String> n = p.getNameOptional();      // static-type assertion (default suffix)
    java.util.Optional<Integer> a = p.getAgeOptional();
    if (!p.getNameOptional().equals(java.util.Optional.empty())) throw new AssertionError("getNameOptional");
    if (!p.getAgeOptional().equals(java.util.Optional.of(42))) throw new AssertionError("getAgeOptional");
    if (p.getRank() != 7) throw new AssertionError("getRank");
    System.out.println("SMOKE OK: nameOptional=" + p.getNameOptional() + " ageOptional=" + p.getAgeOptional() + " rank=" + p.getRank());
  }
}
JAVA
javac -proc:full -cp "$OUT_JAR" -d "$ST" "$ST/Person.java"
java -cp "$ST:$OUT_JAR" Person

# 5b. Negative: @OptionalGetter on a primitive must be rejected with a clear error.
cat > "$ST/Bad.java" <<'JAVA'
public class Bad { @lombok.OptionalGetter private int count; }
JAVA
if javac -proc:full -cp "$OUT_JAR" -d "$ST" "$ST/Bad.java" 2>"$ST/bad.log"; then
  echo "SMOKE FAIL: @OptionalGetter on a primitive should not compile" >&2; cat "$ST/bad.log" >&2; exit 1
fi
if grep -q 'primitive' "$ST/bad.log"; then
  echo "SMOKE OK: primitive field rejected -> $(grep -m1 primitive "$ST/bad.log" | sed 's/^.*error: //')"
else
  echo "SMOKE FAIL: primitive rejected but without the expected message:" >&2; cat "$ST/bad.log" >&2; exit 1
fi

# 5c. Config: lombok.optionalGetter.suffix overrides the accessor-name suffix (proves the config key is registered).
CT="$OUT_DIR/configtest"; mkdir -p "$CT"
printf 'lombok.optionalGetter.suffix = Opt\n' > "$CT/lombok.config"
cat > "$CT/Cfg.java" <<'JAVA'
public class Cfg {
  @lombok.OptionalGetter private String tag;
  Cfg(String t) { this.tag = t; }
  public static void main(String[] x) {
    java.util.Optional<String> t = new Cfg("hi").getTagOpt();   // suffix "Opt" from lombok.config
    if (!new Cfg("hi").getTagOpt().equals(java.util.Optional.of("hi"))) throw new AssertionError("getTagOpt");
    System.out.println("SMOKE OK: lombok.config suffix honoured -> getTagOpt() = " + new Cfg("hi").getTagOpt());
  }
}
JAVA
javac -proc:full -cp "$OUT_JAR" -d "$CT" "$CT/Cfg.java"
java -cp "$CT:$OUT_JAR" Cfg

# 5d. A TYPE_USE @Nullable on the field must NOT be copied onto the Optional<T> return (was a compile error).
ND="$OUT_DIR/nullity"; mkdir -p "$ND"
cat > "$ND/Nn.java" <<'JAVA'
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
public class Nn {
  @Target(ElementType.TYPE_USE) @Retention(RetentionPolicy.CLASS) @interface Nullable {}
  @lombok.OptionalGetter @Nullable String note;
  Nn(String n) { this.note = n; }
  public static void main(String[] x) {
    java.util.Optional<String> n = new Nn(null).getNoteOptional();
    if (!new Nn(null).getNoteOptional().equals(java.util.Optional.empty())) throw new AssertionError("getNoteOptional");
    System.out.println("SMOKE OK: TYPE_USE @Nullable field not leaked onto Optional return -> " + new Nn("x").getNoteOptional());
  }
}
JAVA
javac -proc:full -cp "$OUT_JAR" -d "$ND" "$ND/Nn.java"
java -cp "$ND:$OUT_JAR" Nn
