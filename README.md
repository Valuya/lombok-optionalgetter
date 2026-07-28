# lombok-optionalgetter

[![Maven Central](https://img.shields.io/maven-central/v/io.github.valuya/lombok?label=Maven%20Central)](https://central.sonatype.com/artifact/io.github.valuya/lombok)
[![CI](https://github.com/Valuya/lombok-optionalgetter/actions/workflows/ci.yml/badge.svg)](https://github.com/Valuya/lombok-optionalgetter/actions/workflows/ci.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

Adds a single annotation, **`@lombok.OptionalGetter`**, to [Project Lombok](https://projectlombok.org/):
a variant of `@Getter` whose generated accessor returns `java.util.Optional<T>` instead of `T`. It pairs
naturally with a `@NullMarked` / JSpecify codebase where a nullable field should surface to callers as an
`Optional` rather than a possibly-`null` reference.

```java
import lombok.OptionalGetter;

public class Customer {
    @OptionalGetter private String vatNumber;   // -> Optional<String> getVatNumberOptional()  (Optional.ofNullable)
    @OptionalGetter private Contact billing;    // -> Optional<Contact> getBillingOptional()    (Optional.ofNullable)
}
```

- Reference field `T` -> `Optional<T>`, wrapped with `Optional.ofNullable(this.field)`.
- **The accessor is named `getFooOptional()` by default** (a configurable suffix), so it sits alongside a plain
  `@Getter`'s `getFoo()` without colliding and matches the common `getXxxOptional()` convention. Configure via
  `lombok.config`:
  ```
  lombok.optionalGetter.suffix = Optional   # default; @OptionalGetter on `foo` -> getFooOptional()
  # lombok.optionalGetter.suffix =          # empty -> getFoo()
  ```
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

`build.sh` runs four smoke tests through the built jar (`javac -proc:full`, the same path a real build uses):
the generated `Optional` accessors behave (null reference -> `Optional.empty`); `@OptionalGetter` on a
primitive fails to compile with a clear error; `lombok.optionalGetter.suffix` in `lombok.config` is honoured;
and a field's `@Nullable` (incl. JSpecify TYPE_USE) is **not** copied onto the `Optional<T>` return.

## Quick start

The overlay is published to **Maven Central** as `io.github.valuya:lombok`, a drop-in replacement for
`org.projectlombok:lombok` that additionally understands `@OptionalGetter`. The version is `<upstream lombok
version>.<overlay patch>` (e.g. `1.18.46.3` overlays upstream lombok `1.18.46`).

**1. Replace the lombok coordinate** — no extra repository or credentials, Central is resolved by default:

```xml
<dependency>
  <groupId>io.github.valuya</groupId>
  <artifactId>lombok</artifactId>
  <version>1.18.46.3</version>
  <scope>provided</scope>
</dependency>
```

Gradle: `compileOnly 'io.github.valuya:lombok:1.18.46.3'` + `annotationProcessor 'io.github.valuya:lombok:1.18.46.3'`.

**2. (optional) Pick the accessor name** in `lombok.config` at your project root — the default is already the
`Optional` suffix:

```
# @OptionalGetter on `foo` -> getFooOptional()  (default)
lombok.optionalGetter.suffix = Optional
# set it empty for the plain name: getFoo()
```

**3. Annotate a field** and use the generated accessor:

```java
public class Customer {
    @OptionalGetter private String vatNumber;      // generated: Optional<String> getVatNumberOptional()

    void print() {
        getVatNumberOptional().ifPresent(System.out::println);   // no null check needed
    }
}
```

That's it. Everything else in your build (all existing `@Getter`, `@Builder`, … usage) is unchanged, because
this jar *is* official lombok with one annotation added.

> The overlay is also published to this repo's GitHub Packages registry
> (`https://maven.pkg.github.com/Valuya/lombok-optionalgetter`) as a secondary mirror; Maven Central is the
> primary distribution and needs no repository configuration.

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

## Changelog

- **1.18.46.3** — `@Nullable` (and other nullness markers) are no longer copied onto the `Optional<T>` return;
  a JSpecify TYPE_USE `@Nullable` on `Optional<T>` was a javac error. Proven across ~1,500 accessors in a real
  codebase.
- **1.18.46.2** — configurable accessor suffix via `lombok.optionalGetter.suffix` (default `Optional`, so
  `@OptionalGetter` on `foo` generates `getFooOptional()`).
- **1.18.46.1** — first Maven Central release: `@OptionalGetter`, primitives rejected, javac + eclipse handlers.

## License

MIT — see [LICENSE](LICENSE). This project redistributes [Project Lombok](https://projectlombok.org/) (also MIT)
overlaid with the additional annotation and handlers; the added sources derive from lombok's own `@Getter`.
