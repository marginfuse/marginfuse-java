# Contributing

## Getting set up

The conformance contract is a submodule, so clone with it:

```bash
git clone --recurse-submodules https://github.com/marginfuse/marginfuse-java
cd marginfuse-java
./gradlew build
```

If you already cloned without it: `git submodule update --init --recursive`.

No local Gradle install is needed; the wrapper handles it. Any JDK 17 or newer
works: the build compiles against the Java 11 API with `release`, so you do not
need an old JDK to target one.

## Before you open a pull request

```bash
./gradlew build
./gradlew conformanceRunner
npm --prefix contract/harness install
npm --prefix contract/harness run conformance java
```

CI runs all of it on Java 11, 17 and 21.

## Four rules worth knowing before you change behavior

**This SDK never throws into application code.** It sits in the request path of
somebody else's product. A transport error goes to the `onError` handler and the
call proceeds. The one exception is `guard`, which propagates whatever your own
callback threw, because your error handling owns provider failures.

**`guard` keeps its callback.** Returning a decision for the caller to act on
reads better and would be wrong: enforcement would depend on remembering a
check, and forgetting once means a blocked request reaches the provider.

**No dependencies, including for JSON.** This is a deliberate cost. If the JSON
layer is missing something, extend it and test it rather than reaching for a
library, because a library here becomes every consumer's problem.

**Behavior is defined in the contract, not here.** The expectations live in
[marginfuse/sdk-contract](https://github.com/marginfuse/sdk-contract) as data,
and every MarginFuse SDK in every language reads the same files. If you are
changing what the SDK does rather than how it does it, the change starts with a
pull request there.

## Style

Match the surrounding code. Four spaces, 100 columns, `-Werror` with
`-Xlint:all`. Comments explain why, not what. No em dashes.
