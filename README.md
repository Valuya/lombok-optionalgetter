# lombok-optionalgetter

Adds a single annotation, **`@lombok.OptionalGetter`**, to [Project Lombok](https://projectlombok.org/):
a variant of `@Getter` whose generated accessor returns `java.util.Optional<T>` instead of `T`. It pairs
naturally with a `@NullMarked` / JSpecify codebase where a nullable field should surface to callers as an
`Optional` rather than a possibly-`null` reference.

```java
import lombok.OptionalGetter;

public class Customer {
    @OptionalGetter private String vatNumber;   // -> Optional<String> getVatNumber()  (Optional.ofNullable)
    @OptionalGetter private Contact billing;    // -> Optional<Contact> getBilling()    (Optional.ofNullable)
}
```

- Reference field `T` -> `Optional<T>`, wrapped with `Optional.ofNullable(this.field)`.
- **Primitive fields are rejected** with a compile error: a primitive is never absent, so an `Optional` around
  it would be meaningless. Use a plain `@Getter` (or a boxed type) for those. At the *type* level
  (`@OptionalGetter` on a class), primitive fields are silently skipped rather than failing the whole class.
- Same knobs as `@Getter`: `value` (`AccessLevel`, incl. `NONE`) and `onMethod`. Placeable on a field or a type.
- Generated methods get lombok's usual `@Generated` / `@SuppressWarnings` / copyable-annotation / Checker-Framework
  treatment, exactly like `@Getter`.

## How this is packaged: an overlay, not a companion jar

You cannot ship a lombok handler in a *separate* jar next to the official one. Lombok's `ShadowClassLoader`
loads annotation handlers only from lombok's **own** jar (an `inOwnBase` filter); a handler in any sibling jar
is silently ignored, however it's packaged (verified empirically both as a plain SPI jar and as a
shadow-suffixed jar).

So this project builds an **overlay jar**: it takes the official published `lombok-<version>.jar` and injects

- `lombok/OptionalGetter.class` (a real public annotation class, like `lombok.Getter`),
- `lombok.javac.handlers.HandleOptionalGetter` (and the Eclipse handler) as `.SCL.lombok` shadow entries,
- one extra line in lombok's in-jar `META-INF/services/lombok.{javac.Javac,eclipse.Eclipse}AnnotationHandler`
  service files.

The result is a drop-in replacement for `org.projectlombok:lombok` that additionally understands
`@OptionalGetter`. Because it only re-wraps the official jar, **it tracks upstream by version number** — bump
`lombok.version` in `pom.xml` (or pass a version to `build.sh`) and rebuild; there is no source fork to rebase.

## Build

Needs a JDK (25 verified) and Maven. `build.sh` resolves the official lombok jar and ecj as Maven dependencies
(from `pom.xml`) — there are no download URLs in the build. The javac handler — the only one a Maven/Gradle/`javac`
build uses — always builds; the Eclipse handler is best-effort (skipped, with a warning, if ecj can't compile it).

```bash
./build.sh                 # overlays pom.xml's lombok.version -> target/lombok-<version>.jar, then smoke-tests
./build.sh 1.18.46         # overlay a specific upstream version instead
```

`build.sh` runs two smoke tests through the built jar (`javac -proc:full`, the same path a real build uses):
a **positive** one asserting the generated `Optional` accessors behave (null reference -> `Optional.empty`), and
a **negative** one asserting `@OptionalGetter` on a primitive fails to compile with the expected error.

## Use it (GitHub Packages)

The publish workflow deploys the overlay as `io.github.valuya:lombok:<upstream-version>` to this repo's GitHub
Packages Maven registry. The artifact coordinate is forked (only the group id) so it never clashes with upstream
on a resolver; the jar's internal lombok version is unchanged.

```xml
<dependency>
  <groupId>io.github.valuya</groupId>
  <artifactId>lombok</artifactId>
  <version>1.18.46</version>
  <scope>provided</scope>
</dependency>
```

Point Maven at `https://maven.pkg.github.com/Valuya/lombok-optionalgetter` (see the repo's Packages page for
the exact `<repository>` / auth snippet).

## Verification status

- **Live `javac -proc:full` annotation processing — verified on JDK 25** (the smoke tests in `build.sh`): a null
  reference field yields `Optional.empty`, a set `Integer` yields `Optional[42]`, stock `@Getter` keeps working
  alongside, and `@OptionalGetter` on a primitive is rejected at compile time. This is the same path a Maven build
  uses. (Since JDK 23, `javac` won't run a processor found only on the plain classpath unless processing is
  requested; a real build puts lombok on the processor path, which does — that is why a bare
  `javac -cp lombok.jar` appears to "do nothing".)
- **Eclipse/ecj handler — compiles (against ecj 3.33.0) and is registered**, mirroring the javac handler. Its
  transform fixture isn't authored yet, so it isn't covered by an automated diff test. Note: IntelliJ uses its own
  lombok plugin and will not recognize a custom annotation regardless of this jar — the accessor appears after a
  build, not in live inspections.

## Layout

| Path | Purpose |
|---|---|
| `src/main/java/lombok/OptionalGetter.java` | the annotation |
| `src/main/java/lombok/javac/handlers/HandleOptionalGetter.java` | javac handler (Maven / Gradle / delombok) |
| `src/main/java/lombok/eclipse/handlers/HandleOptionalGetter.java` | eclipse/ecj handler |
| `build.sh` | resolve deps via Maven, deshadow official jar, compile handlers, inject, repackage, smoke-test |
| `pom.xml` | declares the lombok/ecj dependencies (source of `lombok.version`) and the publish coordinates |
