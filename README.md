# marginfuse-java

[![Maven Central](https://img.shields.io/maven-central/v/com.marginfuse/marginfuse-java)](https://central.sonatype.com/artifact/com.marginfuse/marginfuse-java)
[![ci](https://github.com/marginfuse/marginfuse-java/actions/workflows/ci.yml/badge.svg)](https://github.com/marginfuse/marginfuse-java/actions/workflows/ci.yml)
[![license](https://img.shields.io/badge/license-MIT-blue)](LICENSE)

Server-side SDK for [MarginFuse](https://marginfuse.com): profitability
guardrails for AI SaaS. Connect revenue to per-request AI cost, see gross margin
per customer, and stop loss-making requests before they run.

- **Metadata only, by construction.** The event shape has no field for prompts
  or responses, so they cannot be sent. Not a policy, an absence.
- **Never breaks your app.** It does not throw into your code, and it does not
  block your request on MarginFuse being up. If MarginFuse is unreachable, your
  requests proceed unchanged.
- **Zero dependencies.** Java 11+, standard library only. Nothing to conflict
  with whatever your application already uses.

> **Server side only.** This SDK carries a secret API key. Never ship it in a
> desktop or mobile application, or anything else a user can read.

## Install

Gradle:

```kotlin
implementation("com.marginfuse:marginfuse-java:0.2.0")
```

Maven:

```xml
<dependency>
  <groupId>com.marginfuse</groupId>
  <artifactId>marginfuse-java</artifactId>
  <version>0.2.0</version>
</dependency>
```

## Track an AI call

Monitoring. One call after each AI request, metadata only.

```java
MarginFuse mf = MarginFuse.builder()
        .apiKey(System.getenv("MARGINFUSE_KEY"))
        .build();

mf.track(TrackParams.builder()
        .customerId("cus_8x2m91")   // your Stripe customer id, or your own
        .feature("ai_chat")
        .provider("openai")
        .model("gpt-4.1")
        .usage(Usage.builder()
                .inputTokens(1204)
                .outputTokens(388)
                .build())
        .build());
```

`track` returns immediately and sends on a background thread with retries. In a
batch job or a short-lived process, flush before exiting. The client is
`AutoCloseable`, so try-with-resources does it for you:

```java
try (MarginFuse mf = MarginFuse.builder().apiKey(key).build()) {
    ...
}   // closing flushes
```

An unset field in `Usage` means *not reported*, not "used none": it is left off
the request entirely, because claiming a call used zero input tokens is a
different statement from not knowing what it used.

## Guard a call

Protection. Ask before the call runs, and act on the answer.

```java
GuardOutcome out = mf.guard(
        DecideParams.builder()
                .customerId("cus_8x2m91")
                .feature("ai_chat")
                .provider("openai")
                .model("gpt-4.1")
                .build(),
        decision -> {
            // decision.model() is the one to call: a downgrade verdict changes it.
            var response = client.chat(decision.model(), messages);
            return ProviderCall.builder()
                    .result(response)
                    .usage(Usage.builder()
                            .inputTokens(response.promptTokens())
                            .outputTokens(response.completionTokens())
                            .build())
                    .build();
        });

switch (out.kind()) {
    case COMPLETED -> use(out.result());
    case TOPUP_REQUIRED -> showTopup(out.decision().topupContext());
    case BLOCKED -> showLimitReached();
}
```

One call does the whole loop: ask, run with the resolved model, report the real
cost, acknowledge what your application did.

### Why a callback

Enforcement must not depend on you remembering to check anything. If `guard`
returned a decision for you to act on, forgetting the check once would mean a
blocked request reaches the provider anyway. With a callback that is
structurally impossible: when the verdict is `BLOCK`, your lambda is never
invoked.

### Why decide has no error return

There is no failure a caller should branch on. A decision that times out or
errors is an *allow* with `degraded()` set, because MarginFuse being unreachable
must never become your outage. Transport failures go to the `onError` handler.

## Tell MarginFuse what a customer pays

Margin needs a revenue side. With Stripe connected it comes from there. Without
one, you declare your plans in MarginFuse and say which plan each customer is
on:

```java
Identity id = mf.identify(IdentifyParams.builder()
        .customerId("user_8x2m91")
        .plan("pro")            // the key of a plan you declared in Settings
        .name("Acme Studio")
        .metadata(Map.of("tier", "legacy"))  // labels policies can match on
        .build());

if (!id.ok()) {
    log.warn("MarginFuse identify: {}", id.error());
}
```

Safe to call on every sign-in: sending the plan the customer is already on
changes nothing. Sending a different one ends the current cycle and prorates
what accrued. `periodStart` backdates the cycle for a customer who has been
paying since an earlier date; `clearPlan(true)` takes them off plans.

This is the one call that does not fail open. `track` retries later and
`decide` allows, because both have a safe default; "I could not record what
this customer pays" has none, and a wrong plan is a wrong margin. So it reports
the failure to you instead of swallowing it. It still never throws.

`track`, `guard` and `decide` also accept a `plan`, so it can ride along with
usage rather than needing its own call. There it is a hint: a key that does not
resolve is ignored rather than failing your event.

## OpenRouter and other gateways

Gateways report the real cost of every call. Forward it and your figures are
exact instead of estimated.

```java
// usageMap is whatever your HTTP client decoded from the response's "usage"
OpenRouter.Mapped mapped = OpenRouter.from(usageMap);

mf.track(TrackParams.builder()
        .customerId("cus_8x2m91")
        .feature("ai_chat")
        .provider("openrouter")
        .model("anthropic/claude-sonnet-4.5")
        .usage(mapped.usage())
        .costUsd(mapped.costUsd())
        .build());
```

`OpenRouter.from` takes a `Map` rather than a typed response, so no particular
HTTP or JSON library is implied. Use it rather than mapping the fields yourself:
OpenRouter's `prompt_tokens` already includes cached reads and cache writes,
which MarginFuse prices separately, so passing it through directly charges every
cached token twice at the full input rate. The helper also formats the cost as a
decimal string, because `Double.toString` produces `"1.2E-7"` for small costs
and the API rejects that.

## Configuration

```java
MarginFuse mf = MarginFuse.builder()
        .apiKey(System.getenv("MARGINFUSE_KEY"))
        .baseUrl("https://api.marginfuse.com")   // your own deployment in dev
        .timeout(Duration.ofMillis(1500))        // decide budget before failing open
        .onError((error, context) -> log.warn("marginfuse {}: {}", context, error))
        .httpClient(myClient)                    // proxies, test doubles
        .build();
```

`onError` is the only place transport failures surface. The SDK swallows them so
they cannot become your outage; without the handler they are silent.

## Why no JSON dependency

A library that drags in Jackson or Gson forces its version on every application
that embeds it, and conflicts between transitive copies are a familiar and real
pain in Java. An SDK that sits inside somebody else's build should not start an
argument with it, so this one ships a small JSON reader and writer for exactly
its own traffic, with its own tests.

## What it sends

Everything, and nothing else:

```
eventId  customerId  feature  provider  model  requestedModel
usage { inputTokens, outputTokens, cachedInputTokens,
        cacheCreationTokens, images, audioSeconds }
costUsd  occurredAt  outcome  decisionId  retryOfEventId  correctsEventId
```

There is no field for message content anywhere in the wire types. The
[conformance suite](https://github.com/marginfuse/sdk-contract) checks this
against the bytes that actually leave the process, on every scenario.

## Conformance

This SDK is verified against
[marginfuse/sdk-contract](https://github.com/marginfuse/sdk-contract), the same
contract every MarginFuse SDK in every language is held to. It is a submodule
here, so the pinned commit records exactly which contract a release passed, and
`Contract.VERSION` reports it at runtime.

```bash
git clone --recurse-submodules https://github.com/marginfuse/marginfuse-java
cd marginfuse-java
./gradlew build            # unit tests, plus the shared gateway vectors
./gradlew conformanceRunner
npm --prefix contract/harness install
npm --prefix contract/harness run conformance java
```

## Links

- [MarginFuse](https://marginfuse.com), product and pricing
- [Documentation](https://marginfuse.com/docs)
- [API reference](https://api.marginfuse.com/openapi.json)
- [Security policy](SECURITY.md)
- [Contributing](CONTRIBUTING.md)

MIT, Pemira Labs.
