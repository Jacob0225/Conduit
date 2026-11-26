# Mixin splitter

Applies a hot-loaded mod's mixins to Minecraft classes that are already loaded, on a standard JVM. Code: `loader/src/main/java/dev/conduit/hotload/mixin/` and `runtime/HotRuntime.java`.

## Result

The `testmod-hotmixin` test mod has one mixin per case. All its targets are already loaded at the title screen, except one that loads later. It was hot-loaded into a running NeoForge 1.21.1 client: **21 of 21 checks passed**, server join included (`docs/splitter-report.txt`).

| Case | Target | Result |
| --- | --- | --- |
| `@Inject` at HEAD, handler moved to a companion | `Minecraft.tick` | ✅ runs every tick |
| `@Unique` instance field | `Minecraft` | ✅ side store |
| `@Unique static final` field with initializer | `Minecraft` | ✅ new static-init code extracted and run once |
| Lambda inside a moved handler | `Minecraft` | ✅ method-handle proxy |
| Interface added to a loaded class + its methods | `Minecraft implements TickCounter` | ✅ `instanceof` and calls work |
| `@Accessor` getter and setter on a private field | `Minecraft.rightClickDelay` | ✅ |
| `@Invoker` on a private method | `Minecraft.isMultiplayerServer` | ✅ |
| `@ModifyArg` | `SplashRenderer.render` | ✅ splash shows "Mixin'd! …" |
| `@Inject` at TAIL | `TitleScreen.render` | ✅ |
| Target that loads after the hot-load | `WinScreen` | ✅ normal Mixin path |

The whole hot-load took ~820 ms: attach the agent, prepare the configs, split 3 classes, redefine.

![Splash changed by a hot-loaded @ModifyArg](docs/03-splitter-title.png)

## How it works

1. **Register the configs.** Register the mod's mixin configs with Mixin and run its private `MixinProcessor.select()`. Mixin only picks up new configs before the first class is transformed. Already-loaded targets are hidden from its "loaded too early" check while it prepares them.
2. **Build the patched class.** For each loaded target, ModLauncher's `getMaybeTransformedClassBytes` rebuilds the class from its original bytes through the full transformer chain, using the real Mixin. Injection semantics are never reimplemented.
3. **Split it** (`MixinSplitter`):
   - **Method present before:** take the patched body and redefine it.
   - **New method** (handlers, `@Unique`, interface and accessor implementations, lambda bodies): moved into a hidden **nestmate** companion as a static method, with `this` as the first parameter. Nestmate access lets it use the target's private members.
   - **New field:** stored in `HotRuntime`'s weak identity side store.
   - **New interface:** recorded, and implementations are dispatched by receiver class.
   - **New static-init code:** a longest-common-subsequence diff against the loaded `<clinit>`; the new part runs once.
   - All classes are redefined in one atomic `redefineClasses` call.
4. **Rewrite call sites** (`CallSiteRewriter`) in patched bodies, companions, and every class of the hot-loaded mod as it loads (a `ClassFileTransformer`):
   - Calls to moved methods become `invokedynamic`.
   - Interface calls, `instanceof` and casts go through `HotRuntime`.
   - Field reads and writes go to the side store.

## Lessons learned

- `LambdaMetafactory` (JDK 21) puts the implementation class's name in the lambda class it generates. A hidden companion can't be found by name, so lambdas over moved code use `MethodHandleProxies` instead.
- Hierarchy lookups must never load classes: they run while a class is being defined. `Hierarchy` reads superclass names from class files instead.
- A second hot-load hitting the same class reuses existing field slots and companion bindings, and never re-runs static init.

## Known limits

- A mixin that adds an **override** of an inherited method: virtual dispatch can't reach a companion. Detected and rejected.
- **Constructors** added by mixins, **super calls** inside moved code, and **`altMetafactory`** lambdas (serializable or marker interfaces): rejected.
- **Existing objects** start new fields at default values. Mixin's field initializers only run for objects created afterwards.
- Static-init code with branches isn't extracted (noted, skipped).

## Next for Create

Ponder, Flywheel and Create also need:

1. **Jar-in-jar:** Create bundles Flywheel, Ponder and Registrate, a plain library that needs to become a module.
2. **Access transformers on loaded classes:** Create has 55 entries, Ponder 3. The approach:
   - Reroute the mod's accesses to private members and classes through method handles.
   - Write final fields with privileged access.
   - Add a dispatch shim for mods overriding a method whose `final` the AT removed. Ponder does this with `AbstractWidget.render`.
   - The same shim also fixes the "added override" limit above.
3. **Lifecycle replay:** common and client setup, renderers, particles, menus, key mappings, shaders (Flywheel), network payloads (needed to join a Create server), and configs.
