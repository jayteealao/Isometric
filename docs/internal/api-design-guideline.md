# API Design Guideline

## Purpose

This document defines practical guidelines for designing library APIs with strong ergonomics and developer experience (DX). The goal is to make the common case obvious, the advanced case possible, and the dangerous case explicit.

Good APIs should be:
- easy to learn
- easy to read in existing code
- hard to misuse
- stable over time
- flexible without being noisy

These guidelines are primarily for library and SDK design, but most of them also apply to service APIs.

## Core Design Principle

Design the API so that a new user can succeed quickly, an experienced user can customize behavior without friction, and an expert user can reach lower-level primitives when necessary.

A useful rule:

> Make the easy thing easy, the right thing the default, and the weird thing possible without infecting the whole API.

## 1. Start with the Hero Scenario

Design the API around the most important real user workflows, not around every internal capability.

### What to do
- Identify the top 1 to 3 tasks users want to accomplish first.
- Design those tasks before designing advanced features.
- Make the shortest successful path obvious.
- Provide examples that mirror those core workflows.

### What to avoid
- Exposing every internal subsystem at the top level.
- Designing around implementation structure instead of user intent.
- Adding speculative features before validating real use cases.

### Good smell
A user can achieve a meaningful result in a few lines of code.

### Bad smell
The first successful example requires factories, registries, providers, policies, and an options object that looks like it escaped from a government form.

## 2. Use Progressive Disclosure

The API should reveal complexity in layers.

### Recommended layers
1. **Simple path** for common usage with minimal setup.
2. **Configurable path** for common variations.
3. **Low-level path** or escape hatch for advanced control.

### Example
Instead of forcing users into one giant entry point:

```ts
client.fetch(url, {
  timeoutMs: 5000,
  retryPolicy: ..., 
  serializer: ..., 
  transport: ..., 
  authStrategy: ..., 
  cacheMode: ..., 
  hooks: ...
})
```

Prefer a layered design:

```ts
client.fetch(url)
client.fetch(url, { timeoutMs: 5000 })
client.rawRequest(request)
```

### What to avoid
- Giant option bags for simple tasks.
- Builders for trivial operations.
- Advanced knobs leaking into beginner workflows.

## 3. Make Defaults Do the Right Thing

Defaults are part of the API design, not a side detail.

### Good defaults should be
- safe
- predictable
- production-friendly
- easy to explain

### Areas where defaults matter
- timeouts
- retries
- validation
- resource cleanup
- concurrency behavior
- serialization
- logging
- security-sensitive behavior

### Rule
A user should not need to read deep documentation to avoid obvious footguns.

### What to avoid
- silent infinite retries
- no timeout where a timeout is expected
- insecure default behavior for convenience
- hidden global state
- behavior that changes drastically across environments

## 4. Name Things for User Intent

Names should reflect what the user is trying to do, not how the internals happen to work.

### Good naming rules
- Use one term per concept.
- Use different names for genuinely different concepts.
- Prefer specific, descriptive names over generic ones.
- Avoid throwaway words like `data`, `payload`, `responseObject`, `manager`, and `helper` unless they are truly meaningful.
- Avoid abbreviations unless they are widely understood in the language or domain.

### Good examples
- `getUserById`
- `cache.getOrLoad`
- `sendEmail`
- `createdAt`

### Bad examples
- `doThing`
- `handle`
- `processData`
- `manager.execute`

### Naming consistency checklist
- Is the same concept always named the same way?
- Are boolean names clear?
- Are collection names plural?
- Do method names communicate action?
- Can a reader guess what a call does without opening docs?

## 5. Optimize for Readability in Real Code

Most developers spend more time reading code than inventing it.

The API should read well in the places where it will actually be used.

### Prefer
- intention-revealing method names
- explicit parameter names where supported
- argument order that matches normal mental models
- separate methods when behaviors differ meaningfully

### Avoid
- mystery booleans
- overloaded methods with unrelated modes
- giant fluent chains that hide intent
- APIs that are technically flexible but painful to scan

### Better
```python
image.resize(width=300, height=200)
```

### Worse
```python
image.process(300, 200, True, False, None, "fit")
```

## 6. Make Invalid States Hard to Express

A well-designed API should prevent nonsense as early as possible.

### Prefer
- typed options over free-form maps
- separate APIs for separate modes
- construction-time validation
- lifecycle modeling that enforces valid order

### Avoid
- allowing contradictory flags together
- requiring users to guess which fields are mutually exclusive
- pushing all mistakes to runtime when the design could prevent them

### Example
If two modes are mutually exclusive, prefer separate methods or types over one option object with conflicting fields.

### Goal
The API should guide users toward valid usage instead of acting like a vending machine for broken states.

## 7. Design Errors as a First-Class Part of the API

Errors are not an afterthought. They are part of the contract.

### Good errors should answer
- What went wrong?
- Why did it happen?
- Is it a usage error or a runtime problem?
- What should the caller do next?

### Good error design
- distinguish caller mistakes from runtime failures
- provide structured error types or codes where useful
- include actionable context
- preserve underlying causes where possible
- make recoverable situations detectable

### Avoid
- `Invalid argument`
- `Something went wrong`
- inconsistent error models across similar operations
- forcing callers to parse human prose when machine-readable information is needed

### Better
```text
Query parameter `limit` must be less than or equal to 1000.
```

### Worse
```text
Bad request.
```

## 8. Prefer Composition Over God Objects

Large central objects often become junk drawers.

### Prefer
- a small top-level API
- focused submodules or subclients
- reusable primitives
- separation by responsibility

### Avoid
- a single client with unrelated methods for every possible concern
- hidden coupling between modules
- giant interfaces that are hard to test and hard to understand

### Good pattern
- high-level root client for common entry
- focused modules for related tasks
- lower-level primitives for advanced control

## 9. Always Provide Escape Hatches

High-level convenience is great, until it traps advanced users.

### The API should allow users to
- access low-level primitives
- customize transport/serialization when needed
- hook into lifecycle events if appropriate
- bypass convenience layers without rewriting everything

### Avoid
- forcing every user through the same abstraction
- exposing no raw access for uncommon but valid use cases
- making the only escape hatch a fork of the library, which is not really a hatch, more a cry for help

### Rule
Convenience layers should sit on top of primitives, not replace them.

## 10. Respect the Host Language

A good API should feel native in its language.

### Check for idiomatic fit
- Does it follow language naming conventions?
- Does it use the normal async model?
- Does it handle resources the way the ecosystem expects?
- Does error handling match common practice?
- Does it compose the way developers in that language expect?

### Examples
- Python should usually feel simple, readable, and direct.
- Rust should model ownership and failure explicitly.
- Kotlin should make good use of named args, builders only where justified, and nullability semantics.
- Go should avoid ornamental abstraction and prefer clear small interfaces.

### Avoid
Importing patterns from another language just because they looked sophisticated there.

## 11. Design for Evolution

APIs live longer than the first implementation. Design so that the API can grow without breaking users.

### Prefer
- additive changes over breaking changes
- option objects when future expansion is likely
- extensible enums where appropriate
- stable semantics for existing calls
- deprecation paths with clear migration guidance

### Watch out for
- public types that expose internals
- argument lists that are hard to extend
- brittle enums that will obviously grow
- return types that lock the design too early
- hidden assumptions that make future pagination, caching, concurrency, or batching painful

### Rule
Do not make users rewrite working code just because the API was painted into a corner.

## 12. Dogfood the API

The fastest way to discover a bad API is to use it.

### Do this before release
- build a real example app
- write the getting-started guide yourself
- test error handling flows
- test advanced customization paths
- review the API in both success and failure cases

### Look for
- places where docs are compensating for bad design
- points where the user has to remember hidden rules
- places where the code feels awkward even when it works

### Rule
If the maintainers avoid their own high-level API, users will notice the smell immediately.

## Review Checklist

Use this checklist when reviewing an API surface.

### First-use experience
- Can a beginner succeed in a few lines?
- Is the first useful workflow obvious?
- Are unnecessary concepts exposed too early?

### Progressive disclosure
- Is there a clean simple path?
- Is customization possible without massive ceremony?
- Are low-level primitives available for advanced cases?

### Defaults
- Are defaults safe and predictable?
- Do they avoid obvious performance or security problems?
- Are risky behaviors explicit?

### Naming
- Are names consistent and specific?
- Do methods read like intent?
- Are there generic or misleading names?

### Correctness and misuse resistance
- Can invalid combinations be expressed?
- Are mistakes caught early?
- Is lifecycle order clear and enforced?

### Errors
- Are errors actionable?
- Are caller mistakes clearly separated from runtime failures?
- Can callers handle recoverable conditions cleanly?

### Modularity
- Is the surface decomposed into coherent parts?
- Are there god-objects or overgrown modules?
- Does composition feel natural?

### Evolution
- Can the API grow without frequent breakage?
- Are deprecations manageable?
- Are future options likely to fit naturally?

## Recommended Design Process

1. Define the hero scenarios.
2. Write the ideal usage examples before finalizing the API.
3. Design the simple path first.
4. Add the configurable path second.
5. Add low-level primitives only where genuinely needed.
6. Review naming for consistency.
7. Review defaults for safety.
8. Review misuse cases and invalid states.
9. Review error messages and error structure.
10. Dogfood the API in a small real project.
11. Trim unnecessary surface area.
12. Document the common path first, then advanced paths.

## Non-Goals

This guide does not argue for maximal abstraction, maximal flexibility, or maximal cleverness. Those often produce APIs that impress maintainers and annoy users.

The goal is not to expose every possible capability at the top level. The goal is to produce an API that helps developers do useful work with minimal confusion and minimal surprise.

## References

This guide is informed in part by Microsoft’s API design guidance, especially its emphasis on developer experience, hero scenarios, naming consistency, avoiding surprises, error design, and long-term compatibility:
- Microsoft, *Considerations for Service Design*: https://github.com/microsoft/api-guidelines/blob/vNext/azure/ConsiderationsForServiceDesign.md

