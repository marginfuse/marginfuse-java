# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and this project
follows [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [0.2.0]

### Added

- `identify`: tell MarginFuse who a customer is and which plan they are on.

  MarginFuse can now compute margin without a revenue source connected, from
  plans you declare in Settings and a plan assigned per customer. This call is
  how your application assigns that plan itself.

  ```java
  Identity id = mf.identify(IdentifyParams.builder()
          .customerId("user_8x2m91")
          .plan("pro")
          .name("Acme Studio")
          .build());
  ```

  `plan` is the key of a plan declared in MarginFuse, not a Stripe price id.
  Safe to call on every sign-in: sending the plan the customer is already on
  changes nothing. `periodStart` backdates the cycle, `clearPlan` ends it.

  Unlike `track`, this one reports failure instead of failing quietly. A wrong
  plan is a wrong margin, and there is no safe default for "I could not record
  what this customer pays". Check `id.ok()`; `onError` is called too. It still
  never throws into your code.

- `plan` on `TrackParams` and `DecideParams`, so a plan can ride along with
  usage rather than needing its own call. There it is a hint: a key that does
  not resolve is ignored rather than failing your event, because usage must
  never be lost to a plan note.

Both are additive. Existing code keeps working unchanged.

## [0.1.0]

First release. Java 11+, zero dependencies, standard library only.

### Added

- `MarginFuse.track` reports an AI call that already happened. Returns
  immediately, sends on a background thread with retries, and never throws into
  application code.
- `MarginFuse.decide` asks whether the next call should run. Fails open to
  `Action.ALLOW` with `degraded()` set on any timeout or error.
- `MarginFuse.guard` does the whole loop: ask, run your callback with the
  resolved model, report the real cost, acknowledge what the application did.
- `MarginFuse.flush` and `AutoCloseable`, for jobs that would otherwise exit
  before their last events are sent.
- `OpenRouter.from` maps an OpenRouter usage object, including the gateway's own
  cost, so gateway figures are exact rather than estimated.
- `Contract.VERSION` reports the shared contract this build was verified
  against.

### Notes on the design

- **Zero dependencies, including for JSON.** A library that drags in Jackson or
  Gson forces its version on every application that embeds it, and conflicts
  between transitive copies are a familiar pain in Java. This ships a small
  reader and writer for exactly its own traffic, with its own tests for the
  escapes, the number ranges and the malformed input that a gateway error page
  produces.
- **`decide` has no error return.** A failed decision is not a condition to
  branch on: it is an allow with `degraded()` set, because MarginFuse being
  unreachable must never become your outage.
- **`guard` takes a callback.** If it returned a decision to act on, forgetting
  the check once would let a blocked request reach the provider.
- **An unset `Usage` field means not reported.** It is omitted from the request
  rather than sent as zero, because those are different claims.
- **`release = 11` rather than a toolchain**, so the build compiles against the
  real Java 11 API on whatever JDK a contributor already has, instead of making
  everyone install an old one.
- Verified against
  [marginfuse/sdk-contract](https://github.com/marginfuse/sdk-contract): 16
  behavioral scenarios and 13 gateway vectors, the same ones the Node, Python
  and Go SDKs pass.
