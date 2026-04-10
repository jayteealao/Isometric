# Isometric — Claude Persistent Context

## API Design

`docs/internal/api-design-guideline.md` is the **authoritative guide** for every public API decision in this project.
Before proposing or writing any public-facing interface, class, extension property, or function, re-read the
relevant sections of that document and verify compliance. The twelve sections cover:

1. Hero Scenario — design around the simplest real use case first
2. Progressive Disclosure — simple / configurable / low-level layers
3. Defaults — safe, predictable, no surprises
4. Naming — express user intent, not implementation detail
5. Readability — call-site clarity over declaration convenience
6. Invalid States — make invalid combinations impossible or a compile error
7. Errors — surface problems at the earliest possible moment
8. Composition — prefer small composable pieces over god objects
9. Escape Hatches — always let power users drop to lower levels
10. Host Language — idiomatic Kotlin; use named args, nullability, avoid unnecessary builders
11. Evolution — additive changes; never silently break semantics
12. Dogfooding — use the API yourself before shipping it

## WebGPU Vendor Source

`vendor/androidx-webgpu/` contains a **read-only snapshot** of the real `androidx.webgpu` library
(downloaded from `https://android.googlesource.com/platform/frameworks/support/+archive/refs/heads/androidx-main/webgpu.tar.gz`).

Use it to:

- Verify exact class, function, and parameter names before referencing them in plans or code
- Understand suspend-function signatures, callback shapes, and enum values
- Check for API-breaking differences between alpha releases

To refresh the snapshot:
```bash
curl -L "https://android.googlesource.com/platform/frameworks/support/+archive/refs/heads/androidx-main/webgpu.tar.gz" \
     -o webgpu-source.tar.gz
tar -xzf webgpu-source.tar.gz -C vendor/androidx-webgpu/ --strip-components=0
rm webgpu-source.tar.gz
```

The folder is `.gitignore`d — it is never committed.

## Implementation Plans

Active plans live in `docs/internal/plans/`. The primary WebGPU roadmap is:

- `docs/internal/plans/2026-03-18-webgpu-roadmap.md`

Always keep plans in sync with the actual vendor API and the api-design-guideline.
