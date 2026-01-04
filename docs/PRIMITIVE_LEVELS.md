# Understanding the Two Levels of the Runtime API

## Overview

The runtime API provides **two levels of abstraction**:

1. **High-Level API** - Convenient composables (`Shape`, `Group`, etc.)
2. **Low-Level Primitives** - Direct `ComposeNode` usage

Both are valid! The choice depends on your needs.

---

## Level 1: High-Level API (Recommended)

### What It Looks Like

```kotlin
IsometricScene {
    Shape(
        shape = Prism(Point(0, 0, 0)),
        color = IsoColor(255, 0, 0),
        rotation = PI / 4
    )
}
```

### How It Works Internally

The `Shape` composable is actually this:

```kotlin
@Composable
fun IsometricScope.Shape(
    shape: Shape,
    color: IsoColor = LocalDefaultColor.current,
    // ... other params
) {
    // THIS is the low-level primitive!
    ReusableComposeNode<ShapeNode, IsometricApplier>(
        factory = { ShapeNode(shape, color) },
        update = {
            set(shape) { this.shape = it; markDirty() }
            set(color) { this.color = it; markDirty() }
            set(position) { this.position = it; markDirty() }
            // ... etc
        }
    )
}
```

### When to Use

✅ **Use the high-level API when:**
- You want a clean, easy-to-use API
- You're building standard scenes
- You want sensible defaults
- You want CompositionLocal support (default colors, etc.)
- You're learning the library

---

## Level 2: Low-Level Primitives (Advanced)

### What It Looks Like

```kotlin
@Composable
fun MyCustomPrimitive() {
    ComposeNode<ShapeNode, IsometricApplier>(
        factory = {
            ShapeNode(
                shape = Prism(Point(0, 0, 0)),
                color = IsoColor(255, 0, 0)
            )
        },
        update = {
            // You control EXACTLY how updates work
            set(myCustomLogic) {
                // Custom behavior here
                this.shape = calculateShape()
                markDirty()
            }
        }
    )
}
```

### When to Use

✅ **Use low-level primitives when:**
- You need **custom behavior** not provided by the high-level API
- You want to **optimize** for a specific use case
- You're creating **your own composable library** on top
- You need **precise control** over when nodes update
- You want to **extend** the system with new node types

---

## Key Differences

| Aspect | High-Level API | Low-Level Primitives |
|--------|---------------|---------------------|
| **Ease of Use** | ✅ Very easy | ⚠️ Requires understanding of Compose internals |
| **Code Length** | ✅ Concise | ⚠️ More verbose |
| **Flexibility** | ⚠️ Limited to provided features | ✅ Complete control |
| **Type Safety** | ✅ Strongly typed | ✅ Strongly typed |
| **Performance** | ✅ Optimized | ✅ Can be more optimized with custom logic |
| **Defaults** | ✅ Sensible defaults | ❌ Must specify everything |
| **CompositionLocals** | ✅ Automatic | ❌ Manual access needed |

---

## Example: Building Custom Primitives

### Example 1: Custom Shape Behavior

Let's say you want a shape that **automatically pulses** its scale:

#### Using High-Level API (Limited)

```kotlin
@Composable
fun PulsingShape() {
    var scale by remember { mutableStateOf(1.0) }

    LaunchedEffect(Unit) {
        while (true) {
            delay(16)
            scale = 1.0 + sin(System.currentTimeMillis() / 500.0) * 0.2
        }
    }

    Shape(
        shape = Prism(Point(0, 0, 0)),
        color = IsoColor(255, 0, 0),
        scale = scale  // ✅ Works, but creates extra state
    )
}
```

#### Using Low-Level Primitives (More Efficient)

```kotlin
@Composable
fun PulsingShape() {
    ComposeNode<ShapeNode, IsometricApplier>(
        factory = {
            ShapeNode(
                shape = Prism(Point(0, 0, 0)),
                color = IsoColor(255, 0, 0)
            )
        },
        update = {
            // Custom update logic that runs on every frame
            update {
                // Calculate scale directly without extra state
                this.scale = 1.0 + sin(System.currentTimeMillis() / 500.0) * 0.2
                markDirty()
            }
        }
    )
}
```

---

### Example 2: Creating a Custom Node Type

You can create **entirely new node types** by extending `IsometricNode`:

```kotlin
// 1. Define custom node
class TextNode(
    var text: String,
    var fontSize: Double,
    var color: IsoColor
) : IsometricNode() {
    override val children = mutableListOf<IsometricNode>()

    override fun render(context: RenderContext): List<RenderCommand> {
        // Custom rendering logic for 3D text
        return emptyList()
    }

    override fun hitTest(x: Double, y: Double, context: RenderContext): IsometricNode? {
        // Custom hit testing
        return null
    }
}

// 2. Create composable using ComposeNode
@Composable
fun IsometricScope.Text3D(
    text: String,
    fontSize: Double = 12.0,
    color: IsoColor = LocalDefaultColor.current
) {
    ComposeNode<TextNode, IsometricApplier>(
        factory = { TextNode(text, fontSize, color) },
        update = {
            set(text) { this.text = it; markDirty() }
            set(fontSize) { this.fontSize = it; markDirty() }
            set(color) { this.color = it; markDirty() }
        }
    )
}

// 3. Use it!
IsometricScene {
    Text3D("Hello 3D!", fontSize = 24.0)
}
```

---

### Example 3: Custom Update Logic

Control **exactly when** and **how** a node updates:

```kotlin
@Composable
fun SmartShape(shape: Shape, color: IsoColor) {
    ComposeNode<ShapeNode, IsometricApplier>(
        factory = { ShapeNode(shape, color) },
        update = {
            // Only update if shape actually changed (custom comparison)
            set(shape) {
                if (!shapesAreEqual(this.shape, it)) {
                    this.shape = it
                    markDirty()
                }
            }

            // Custom color update with threshold
            set(color) {
                if (colorDifference(this.color, it) > 0.1) {
                    this.color = it
                    markDirty()
                } else {
                    // Skip update if difference is too small
                }
            }
        }
    )
}
```

---

## Can You Mix Both?

**Absolutely!** You can use both in the same project:

```kotlin
IsometricScene {
    // High-level API for most things
    Shape(Prism(Point(0, 0, 0)), IsoColor(255, 0, 0))

    Group(rotation = angle) {
        Shape(Pyramid(...), color)
    }

    // Drop down to low-level when needed
    MyCustomPrimitive()

    // Custom node type
    Text3D("Score: 100")
}
```

---

## The Full Stack

Here's what's happening at each level:

```
┌─────────────────────────────────────────────────┐
│ Level 3: Your App Code                          │
│ IsometricScene { Shape(...) }                   │
├─────────────────────────────────────────────────┤
│ Level 2: High-Level Composables (what I built)  │
│ Shape, Group, Path composables                  │
├─────────────────────────────────────────────────┤
│ Level 1: Low-Level Primitives (Compose Runtime) │
│ ComposeNode, ReusableComposeNode                │
├─────────────────────────────────────────────────┤
│ Level 0: Custom Infrastructure (what I built)   │
│ IsometricNode, IsometricApplier                 │
├─────────────────────────────────────────────────┤
│ Compose Runtime (Google's code)                 │
│ Composition, Recomposer, SlotTable              │
└─────────────────────────────────────────────────┘
```

You can access:
- **Level 3** - Normal usage
- **Level 2** - Provided composables
- **Level 1** - `ComposeNode` directly
- **Level 0** - Create custom node types

---

## Best Practices

### Start High, Go Low When Needed

1. **Start with high-level API** - Use `Shape`, `Group`, etc.
2. **Identify limitations** - Find cases where you need custom behavior
3. **Drop to low-level** - Use `ComposeNode` for those specific cases
4. **Create abstractions** - Wrap low-level code in your own composables

### Example Workflow

```kotlin
// Day 1: Use high-level API
IsometricScene {
    Shape(Prism(...), color)
}

// Day 2: Realize you need custom behavior
@Composable
fun AnimatedPrism() {
    ComposeNode<ShapeNode, IsometricApplier>(...) {
        // Custom logic
    }
}

// Day 3: Abstract it into a reusable component
@Composable
fun MyLibrary.AnimatedPrism(
    baseShape: Shape,
    animationSpeed: Double = 1.0
) {
    // Your custom composable built on ComposeNode
}

// Day 4: Use your custom component alongside high-level API
IsometricScene {
    Shape(...)  // High-level
    AnimatedPrism(...)  // Your custom primitive
    Group(...) { }  // High-level
}
```

---

## Performance Considerations

### High-Level API Performance

The high-level API is **already optimized**:
- Uses `ReusableComposeNode` for object pooling
- Proper dirty tracking
- Efficient update logic

### When Low-Level Might Be Faster

You might get better performance with low-level primitives if:

1. **You can skip unnecessary checks**
   ```kotlin
   ComposeNode<ShapeNode, IsometricApplier>(
       factory = { ... },
       update = {
           // Skip all the parameter checks the high-level API does
           init { this.shape = computeShape() }
       }
   )
   ```

2. **You can batch updates**
   ```kotlin
   update {
       // Update multiple properties without triggering multiple dirty marks
       this.shape = newShape
       this.color = newColor
       markDirty()  // Only mark dirty once
   }
   ```

3. **You have domain-specific optimizations**
   ```kotlin
   update {
       // Only update if significant change
       if (shouldUpdate(oldValue, newValue)) {
           this.value = newValue
           markDirty()
       }
   }
   ```

---

## Summary

| Feature | High-Level API | Low-Level Primitives |
|---------|---------------|---------------------|
| **Access** | `Shape(...)` | `ComposeNode<ShapeNode, ...>` |
| **Difficulty** | Easy | Advanced |
| **Use Case** | 95% of cases | Custom behavior |
| **Can Mix?** | ✅ Yes | ✅ Yes |
| **Performance** | Already optimized | Can optimize further |
| **Recommended?** | ✅ Start here | ⚠️ When needed |

**Bottom Line:**
- Use the **high-level API** by default
- Drop to **low-level primitives** when you need custom behavior
- **Mix both** freely in the same project
- The high-level API is just a **convenience wrapper** around the low-level primitives

Both approaches are first-class citizens in the architecture!
