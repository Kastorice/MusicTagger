# MiuiX 0.9.4 API Cheat Sheet (HyperOS / Compose Multiplatform)

Authoritative reference for the MusicTagger → MiuiX port. Read from the **v0.9.4 tag source**
(`raw.githubusercontent.com/compose-miuix-ui/miuix/v0.9.4`) and the published `miuix-ui-android-0.9.4`
AAR, not from doc prose. Verified 2026-11.

## 0. Version facts (read first)

| Fact | Value |
|---|---|
| Artifacts | `top.yukonga.miuix.kmp:miuix-ui-android` / `miuix-icons-android` / `miuix-preference-android` |
| `minCompileSdk` (AAR-enforced) | **37** · `minSdkVersion` 24 |
| Compiled with | Kotlin **2.4.20** (`kotlin.Metadata mv=[2,4,0]`), Compose Multiplatform **1.12.0** |
| Transitive Compose | `androidx.compose.foundation:foundation:1.12.0` (animation/runtime/ui 1.12.0) |
| Transitive runtime | `com.materialkolor:material-color-utilities-android:5.0.1`, `androidx.navigationevent:navigationevent-compose-android:1.1.2`, `miuix-core-android`, `miuix-squircle-android` |
| Experimental opt-ins | **none** for anything below (only `ScrollBar` needs `@OptIn(ExperimentalScrollBarApi::class)`) |

```kotlin
implementation("top.yukonga.miuix.kmp:miuix-ui-android:0.9.4")          // theme + all basic components
implementation("top.yukonga.miuix.kmp:miuix-icons-android:0.9.4")        // 156 "extended" icons
implementation("top.yukonga.miuix.kmp:miuix-preference-android:0.9.4")   // *Preference rows
```

> ⚠ Project pins Kotlin **2.3.20**, compose **1.10.3**, MiuiX **0.9.0**. 0.9.4 carries Kotlin-2.4
> metadata: if the build says *"was compiled by a newer version of Kotlin"*, bump Kotlin to 2.4.x.
> Compose gets upgraded to 1.12.0 transitively (0.9.4 calls `LocalOverscrollFactory`, absent from older
> foundation) — align all `androidx.compose.*` to 1.12.x and drop `material3:1.3.2` once ported.
> `compileSdk = 37` is already correct here.

**0.9.0 → 0.9.4 delta** (verified against both tags; matters because the working tree currently resolves
0.9.0): `Scaffold`, `OverlayBottomSheet`/`OverlayDialog` (incl. `renderInRootScaffold`), `TextField`,
`Snackbar`, `Colors`, `TextStyles` are **identical**. Differences: 0.9.0 has an extra
`MiuixTheme(…, smoothRounding: Boolean = true)` parameter + `MiuixTheme.smoothRounding` accessor, both
**removed in 0.9.4** (squircle moved to `LocalSquircleEnabled` in `miuix-squircle`); the artifact
`miuix-shapes-android:0.9.0` became `miuix-squircle-android:0.9.4`; Compose 1.10.3/ Kotlin 2.3.20 →
Compose 1.12.0 / Kotlin 2.4.20. Drop any `smoothRounding = …` argument when bumping to 0.9.4.

## 1. Theme setup

```kotlin
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController
import top.yukonga.miuix.kmp.theme.ColorSchemeMode     // System | Light | Dark | MonetSystem | MonetLight | MonetDark
import top.yukonga.miuix.kmp.theme.ThemeColorSpec      // Spec2021 | Spec2025
import top.yukonga.miuix.kmp.theme.ThemePaletteStyle   // TonalSpot | Neutral | Vibrant | Expressive | Rainbow | FruitSalad | Monochrome | Fidelity | Content

class ThemeController(
    colorSchemeMode: ColorSchemeMode = ColorSchemeMode.System,
    lightColors: Colors = lightColorScheme(), darkColors: Colors = darkColorScheme(),
    keyColor: Color? = null,                            // seed for Monet* modes; null => Android 12+ platform colors
    colorSpec: ThemeColorSpec = ThemeColorSpec.Spec2021, // Spec2025 only honored for TonalSpot/Neutral/Vibrant/Expressive
    paletteStyle: ThemePaletteStyle = ThemePaletteStyle.TonalSpot,
    isDark: Boolean? = null,                            // overrides isSystemInDarkTheme() for System/MonetSystem
)
```
```kotlin
@Composable fun MiuixTheme(controller: ThemeController, textStyles: TextStyles = MiuixTheme.textStyles, content: @Composable () -> Unit)
@Composable fun MiuixTheme(colors: Colors = MiuixTheme.colorScheme, textStyles: TextStyles = MiuixTheme.textStyles, content: @Composable () -> Unit)
```

**Use the `controller` overload** — it is the only one that also provides `LocalContentColor` (correct
default `Icon` tint / text color) plus `LocalIndication` and `LocalOverscrollFactory`.

```kotlin
@Composable
fun MusicTagTheme(mode: ColorSchemeMode = ColorSchemeMode.MonetSystem, keyColor: Color? = null,
                  isDarkOverride: Boolean? = null, content: @Composable () -> Unit) {
    val controller = remember(mode, keyColor, isDarkOverride) {
        ThemeController(colorSchemeMode = mode, keyColor = keyColor, isDark = isDarkOverride)
    }
    MiuixTheme(controller = controller, content = content)
}

MiuixTheme.colorScheme        // Colors   (@Composable @ReadOnlyComposable)
MiuixTheme.textStyles         // TextStyles
MiuixTheme.isDynamicColor     // true only for MonetSystem / MonetLight / MonetDark
MiuixTheme.colorSchemeMode    // ColorSchemeMode? (null when built via the colors overload)
```
`ThemeController` properties are read-only `val`s backed by `mutableStateOf`; `remember` a new controller
(keyed on your state) to change the theme. `currentColors()` is `@Composable`.

## 2. `Colors` — page vs card

52 fields, all `var` backed by `mutableStateOf`. Light / dark reference values included.

| Field | Light / Dark | Purpose |
|---|---|---|
| `primary` / `onPrimary` | `#3482FF` / `#277AF7`; white | accents, selected switch/checkbox/progress |
| `primaryVariant` / `onPrimaryVariant` | `#3482FF` / `#0073DD`; `#AECDFF` / `#99C7F1` | hero/highlight card bg + its secondary text |
| `primaryContainer` / `onPrimaryContainer` | `#5D9BFF` / `#338FE4`; white | filled blocks |
| `secondary` / `onSecondary` | `#E6E6E6` / `#505050`; white | switch off-track, chips |
| `secondaryVariant` / `onSecondaryVariant` | `#F0F0F0` / `#434343`; `#303030` / `#D9D9D9` | **default Button** bg/content |
| `secondaryContainer` / `onSecondaryContainer` | `#F0F0F0` / `#434343`; `#A9A9A9` / `#7C7C7C` | **TextField bg** + its label |
| `secondaryContainerVariant` / `onSecondaryContainerVariant` | `#F0F0F0` / `#4F4F4F`; `#A8A8A8` / `#959595` | subtle fills |
| `tertiaryContainer` / `onTertiaryContainer` | `#EAF2FF` / `#2B3B54`; `#3482FF` / `#4788FF` | info banners |
| `tertiaryContainerVariant` | `#EAF2FF` / `#505050` | subtle info fill |
| `background` / `onBackground` | white / `#242424`; black / `#E6FFFFFF` | **dialogs & bottom sheets** (their default bg) |
| `onBackgroundVariant` | `#8C93B0` / `#787E96` | `SmallTitle` text |
| `surface` / `onSurface` | `#F7F7F7` / black; black / `#F2F2F2` | **page background** (`Scaffold`/`Surface` default) |
| `surfaceVariant` | white / `#242424` | plain panels |
| `surfaceContainer` / `onSurfaceContainer` | white / `#242424`; black / `#E6FFFFFF` | **Cards / rows**, NavigationBar bg |
| `onSurfaceContainerVariant` | `#959595` / `#737373` | dim text on cards |
| `surfaceContainerHigh` / `onSurfaceContainerHigh` | `#E8E8E8` / `#242424`; `#A2A2A2` / `#666666` | pressed / secondary container |
| `surfaceContainerHighest` / `onSurfaceContainerHighest` | `#E8E8E8` / `#2D2D2D`; black / `#E9E9E9` | highest-emphasis panel |
| `onSurfaceSecondary` | `#CC000000` / `#CCFFFFFF` | dialog summary |
| `onSurfaceVariantSummary` | `#99000000` / `#80FFFFFF` | component summary/subtitle |
| `onSurfaceVariantActions` | `#66000000` / `#66FFFFFF` | trailing action icons |
| `disabledOnSurface` | `#B2B2B2` / `#666666` | disabled text |
| `outline` / `dividerLine` | `#D9D9D9` / `#404040`; `#E0E0E0` / `#393939` | borders / `HorizontalDivider` |
| `error` / `onError` | `#E94634` / `#F12522`; white | destructive |
| `errorContainer` / `onErrorContainer` | `#FDF6F4` / `#2E0603`; `#410002` / `#FFDAD6` | error surfaces |
| `windowDimming` | black 30% / black 60% | dialog & sheet scrim |
| `disabledPrimary`, `disabledOnPrimary`, `disabledPrimaryButton`, `disabledOnPrimaryButton`, `disabledPrimarySlider`, `disabledSecondary`, `disabledOnSecondary`, `disabledSecondaryVariant`, `disabledOnSecondaryVariant` | — | one disabled tone per role (button/switch/checkbox/progress) |
| `sliderKeyPoint`, `sliderKeyPointForeground`, `sliderBackground` | — | Slider only |

**Rule:** page = `Scaffold` (uses `surface`); raised/grouped = `Card` (uses `surfaceContainer`);
dialogs/sheets = `background` (already their default — don't override).

## 3. `MiuixTheme.textStyles`

| size | names |
|---|---|
| 32sp | `title1` |
| 24sp | `title2` |
| 20sp | `title3` |
| 18sp | `title4` |
| 17sp | `main` (**default for `Text`**), `paragraph` (`lineHeight 1.2em`, use for body), `headline1`, `button` |
| 16sp | `headline2`, `body1` |
| 14sp | `body2`, `subtitle` (**Bold**) |
| 13sp | `footnote1` |
| 11sp | `footnote2` |

`TextStyles` fields are read-only outside; customise via `defaultTextStyles(...)` / `TextStyles.copy(...)`.

## 4. Components

All in `top.yukonga.miuix.kmp.basic` unless noted. **These names shadow Material 3's** (`Text`, `Icon`,
`Card`, `Button`, `Switch`, `Checkbox`, `Scaffold`, `NavigationBar`, `TopAppBar`, `Surface`, `TabRow`,
`FloatingActionButton`): remove the M3 imports or alias (`import …basic.Text as MiuixText`).

**4.1 Scaffold** — must wrap every screen that hosts sheets/dialogs/snackbars
```kotlin
import top.yukonga.miuix.kmp.basic.Scaffold
@Composable fun Scaffold(
    modifier: Modifier = Modifier, topBar: @Composable () -> Unit = {}, bottomBar: @Composable () -> Unit = {},
    floatingActionButton: @Composable () -> Unit = {}, floatingActionButtonPosition: FabPosition = FabPosition.End,
    floatingToolbar: @Composable () -> Unit = {}, floatingToolbarPosition: ToolbarPosition = ToolbarPosition.BottomCenter,
    snackbarHost: @Composable () -> Unit = {},                  // pass { SnackbarHost(state) }
    popupHost: @Composable () -> Unit = { MiuixPopupHost() },   // never override with {}
    containerColor: Color = MiuixTheme.colorScheme.surface,
    contentWindowInsets: WindowInsets = WindowInsets.systemBars.union(WindowInsets.displayCutout),
    content: @Composable (PaddingValues) -> Unit,
)
// top.yukonga.miuix.kmp.basic.FabPosition: Start | Center | End
// top.yukonga.miuix.kmp.basic.ToolbarPosition: TopStart | CenterStart | BottomStart | TopEnd | … | BottomCenter
```
Apply `content`'s `PaddingValues` to the content root (`Modifier.padding(paddingValues)`). Insets reach
content **only when the matching bar slot is empty** — bars consume them (`defaultWindowInsetsPadding`).

**4.2 Surface**
```kotlin
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.SurfaceDefaults   // Shape = RectangleShape, ShadowElevation = 0.dp
@Composable fun Surface(modifier: Modifier = Modifier, shape: Shape = SurfaceDefaults.Shape,
    color: Color = MiuixTheme.colorScheme.surface, contentColor: Color = MiuixTheme.colorScheme.onSurface,
    border: BorderStroke? = null, shadowElevation: Dp = SurfaceDefaults.ShadowElevation, content: @Composable () -> Unit)
@Composable fun Surface(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    shape: Shape = SurfaceDefaults.Shape, color: Color = …, contentColor: Color = …,
    border: BorderStroke? = null, shadowElevation: Dp = …, interactionSource: MutableInteractionSource? = null,
    indication: Indication? = LocalIndication.current, content: @Composable () -> Unit)
```

**4.3 TopAppBar / SmallTopAppBar**
```kotlin
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.rememberTopAppBarState
import top.yukonga.miuix.kmp.basic.TopAppBarDefaults
// TitlePadding 26.dp, NavigationIconPadding 16.dp, ActionIconPadding 16.dp, CollapsedHeight 52.dp,
// SmallTopAppBarCenterHeight 50.dp, LargeTitleBottomPadding 4.dp, SubtitleBottomPadding 8.dp

@Composable fun TopAppBar(          // collapsing: largeTitle shrinks to title on scroll
    title: String, modifier: Modifier = Modifier, color: Color = MiuixTheme.colorScheme.surface,
    titleColor: Color = MiuixTheme.colorScheme.onSurface,
    largeTitle: String = title, largeTitleColor: Color = MiuixTheme.colorScheme.onSurface,
    subtitle: String = "", subtitleColor: Color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
    navigationIcon: @Composable () -> Unit = {}, actions: @Composable RowScope.() -> Unit = {},
    scrollBehavior: ScrollBehavior? = null, defaultWindowInsetsPadding: Boolean = true,
    titlePadding: Dp = TopAppBarDefaults.TitlePadding,
    navigationIconPadding: Dp = TopAppBarDefaults.NavigationIconPadding,
    actionIconPadding: Dp = TopAppBarDefaults.ActionIconPadding,
    bottomContent: @Composable () -> Unit = {},
)
@Composable fun SmallTopAppBar(     // identical minus largeTitle / largeTitleColor
    title: String, modifier: Modifier = Modifier, color: Color = …, titleColor: Color = …,
    subtitle: String = "", subtitleColor: Color = …, navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {}, scrollBehavior: ScrollBehavior? = null,
    defaultWindowInsetsPadding: Boolean = true, titlePadding: Dp = …, navigationIconPadding: Dp = …,
    actionIconPadding: Dp = …, bottomContent: @Composable () -> Unit = {},
)
@Composable fun MiuixScrollBehavior(
    state: TopAppBarState = rememberTopAppBarState(), canScroll: () -> Boolean = { true },
    snapAnimationSpec: AnimationSpec<Float>? = spring(stiffness = 2500f),
    flingAnimationSpec: DecayAnimationSpec<Float>? = rememberSplineBasedDecay()): ScrollBehavior
```
```kotlin
val scrollBehavior = MiuixScrollBehavior()
Scaffold(topBar = {
    SmallTopAppBar(title = "音乐库", subtitle = "12 首", scrollBehavior = scrollBehavior, actions = {
        IconButton({ onSearch() }) { Icon(MiuixIcons.Search, "搜索") }
        IconButton({ onRefresh() }) { Icon(MiuixIcons.Refresh, "刷新") }
    })
}) { padding -> Content(Modifier.padding(padding)) }
```

**4.4 NavigationBar + NavigationBarItem**
```kotlin
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarItem        // RowScope extension
import top.yukonga.miuix.kmp.basic.NavigationBarDefaults
import top.yukonga.miuix.kmp.basic.NavigationBarDisplayMode // IconAndText (default) | IconOnly | IconWithSelectedLabel
import top.yukonga.miuix.kmp.basic.NavigationItem          // data class (label: String, icon: ImageVector)
@Composable fun NavigationBar(modifier: Modifier = Modifier, color: Color = MiuixTheme.colorScheme.surface,
    showDivider: Boolean = true, defaultWindowInsetsPadding: Boolean = true,
    mode: NavigationBarDisplayMode = NavigationBarDisplayMode.IconAndText,
    content: @Composable RowScope.() -> Unit)
@Composable fun RowScope.NavigationBarItem(selected: Boolean, onClick: () -> Unit,
    icon: ImageVector,                                     // 👈 ImageVector only, no Painter overload
    label: String, modifier: Modifier = Modifier, enabled: Boolean = true,
    colors: NavigationBarItemColors = NavigationBarDefaults.navigationBarItemColors(),
    badge: (@Composable () -> Unit)? = null)
```
```kotlin
NavigationBar {
    NavigationBarItem(tab == 0, { tab = 0 }, MiuixIcons.Playlist, "曲库")
    NavigationBarItem(tab == 1, { tab = 1 }, MiuixIcons.Settings, "设置")
}
```
Floating pill variant: `FloatingNavigationBar(color = surfaceContainer, cornerRadius = 50.dp, …)` +
`FloatingNavigationBarItem(...)` (same item params, not a `RowScope` extension).

**4.5 Card**
```kotlin
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults           // CornerRadius 16.dp, InsideMargin PaddingValues(0.dp)
import top.yukonga.miuix.kmp.utils.PressFeedbackType      // None | Sink | Tilt
@Composable fun Card(modifier: Modifier = Modifier, cornerRadius: Dp = CardDefaults.CornerRadius,
    insideMargin: PaddingValues = CardDefaults.InsideMargin,
    colors: CardColors = CardDefaults.defaultColors(), content: @Composable ColumnScope.() -> Unit)
@Composable fun Card(modifier: Modifier = Modifier, cornerRadius: Dp = CardDefaults.CornerRadius,
    insideMargin: PaddingValues = CardDefaults.InsideMargin, colors: CardColors = CardDefaults.defaultColors(),
    pressFeedbackType: PressFeedbackType = PressFeedbackType.None, showIndication: Boolean = false,
    holdDownState: Boolean = false, onClick: (() -> Unit)? = null, onLongPress: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit)
// CardDefaults.defaultColors(color = surfaceContainer, contentColor = onSurfaceContainer)
```
No `elevation`; `insideMargin` defaults to 0 because children (`BasicComponent`) bring their own 16.dp.

**4.6 BasicComponent** (HyperOS list row)
```kotlin
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.BasicComponentDefaults   // InsideMargin PaddingValues(16.dp)
@Composable fun BasicComponent(modifier: Modifier = Modifier, title: String? = null,
    titleColor: BasicComponentColors = BasicComponentDefaults.titleColor(),      // onBackground
    summary: String? = null,
    summaryColor: BasicComponentColors = BasicComponentDefaults.summaryColor(), // onSurfaceVariantSummary
    startAction: @Composable (() -> Unit)? = null, endActions: @Composable (RowScope.() -> Unit)? = null,
    bottomAction: (@Composable () -> Unit)? = null, insideMargin: PaddingValues = BasicComponentDefaults.InsideMargin,
    onClick: (() -> Unit)? = null, onClickLabel: String? = null, role: Role? = null,
    holdDownState: Boolean = false, enabled: Boolean = true, interactionSource: MutableInteractionSource? = null)
// 2nd overload: same minus title/summary, plus content: @Composable ColumnScope.() -> Unit
```

**4.7 Button / TextButton**
```kotlin
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.ButtonDefaults   // MinWidth 58.dp, MinHeight 40.dp, CornerRadius 16.dp,
                                                    // InsideMargin PaddingValues(horizontal = 16.dp, vertical = 13.dp)
@Composable fun Button(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    cornerRadius: Dp = ButtonDefaults.CornerRadius, minWidth: Dp = ButtonDefaults.MinWidth,
    minHeight: Dp = ButtonDefaults.MinHeight, colors: ButtonColors = ButtonDefaults.buttonColors(),
    insideMargin: PaddingValues = ButtonDefaults.InsideMargin, interactionSource: MutableInteractionSource? = null,
    indication: Indication? = LocalIndication.current, content: @Composable RowScope.() -> Unit)
@Composable fun TextButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier,   // 🚩 String, no content
    enabled: Boolean = true, cornerRadius: Dp = ButtonDefaults.CornerRadius, minWidth: Dp = …,
    minHeight: Dp = …, colors: TextButtonColors = ButtonDefaults.textButtonColors(),
    insideMargin: PaddingValues = …, textStyle: TextStyle? = null,
    interactionSource: MutableInteractionSource? = null, indication: Indication? = LocalIndication.current)
// ButtonDefaults.buttonColors(color = secondaryVariant, disabledColor = disabledSecondaryVariant,
//   contentColor = onSecondaryVariant, disabledContentColor = disabledOnSecondaryVariant)
// ButtonDefaults.buttonColorsPrimary(color = primary, disabledColor = disabledPrimaryButton,
//   contentColor = onPrimary, disabledContentColor = disabledOnPrimaryButton)   ← filled blue
// ButtonDefaults.textButtonColors(...) / textButtonColorsPrimary(...)
```
```kotlin
Button(onClick = ::save, colors = ButtonDefaults.buttonColorsPrimary(), modifier = Modifier.fillMaxWidth()) {
    Text("保存", style = MiuixTheme.textStyles.button)
}
TextButton(text = "取消", onClick = ::dismiss)
```

**4.8 Text**
```kotlin
import top.yukonga.miuix.kmp.basic.Text
@Composable fun Text(text: String, modifier: Modifier = Modifier, color: Color = Color.Unspecified,
    autoSize: TextAutoSize? = null, fontSize: TextUnit = TextUnit.Unspecified, fontStyle: FontStyle? = null,
    fontWeight: FontWeight? = null, fontFamily: FontFamily? = null, letterSpacing: TextUnit = TextUnit.Unspecified,
    textDecoration: TextDecoration? = null, textAlign: TextAlign? = null, lineHeight: TextUnit = TextUnit.Unspecified,
    overflow: TextOverflow = TextOverflow.Clip, softWrap: Boolean = true, maxLines: Int = Int.MAX_VALUE,
    minLines: Int = 1, onTextLayout: ((TextLayoutResult) -> Unit)? = null,
    style: TextStyle = LocalTextStyles.current.main)   // 17.sp
// also (AnnotatedString, …) and (String|AnnotatedString, color: ColorProducer, …)
```
```kotlin
Text("标题", style = MiuixTheme.textStyles.title4, maxLines = 1, overflow = TextOverflow.Ellipsis)
Text("正文", style = MiuixTheme.textStyles.paragraph, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
```

**4.9 SmallTitle**
```kotlin
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.SmallTitleDefaults   // InsideMargin PaddingValues(28.dp, 8.dp)
@Composable fun SmallTitle(text: String, modifier: Modifier = Modifier,
    textColor: Color = MiuixTheme.colorScheme.onBackgroundVariant,
    insideMargin: PaddingValues = SmallTitleDefaults.InsideMargin)
```

**4.10 TextField** (critical — differs from M3!)
```kotlin
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TextFieldDefaults   // CornerRadius 16.dp, InsideMargin DpSize(16.dp, 16.dp)
@Composable fun TextField(value: String, onValueChange: (String) -> Unit, modifier: Modifier = Modifier,
    insideMargin: DpSize = TextFieldDefaults.InsideMargin, colors: TextFieldColors = TextFieldDefaults.textFieldColors(),
    cornerRadius: Dp = TextFieldDefaults.CornerRadius,       // 🚩 no `shape` param
    label: String = "", useLabelAsPlaceholder: Boolean = false,  // 🚩 no `placeholder` param
    enabled: Boolean = true, readOnly: Boolean = false, textStyle: TextStyle = MiuixTheme.textStyles.main,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default, keyboardActions: KeyboardActions = KeyboardActions.Default,
    leadingIcon: @Composable (() -> Unit)? = null, trailingIcon: @Composable (() -> Unit)? = null,
    singleLine: Boolean = false, maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE, minLines: Int = 1,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    onTextLayout: (TextLayoutResult) -> Unit = {}, interactionSource: MutableInteractionSource? = null,
    cursorBrush: Brush = SolidColor(colors.borderColor))
// overloads: (value: TextFieldValue, onValueChange: (TextFieldValue) -> Unit, …) and
//   (state: TextFieldState, inputTransformation, lineLimits: TextFieldLineLimits, outputTransformation,
//    scrollState, onKeyboardAction, …)
// TextFieldDefaults.textFieldColors(backgroundColor = secondaryContainer, labelColor = onSecondaryContainer,
//   borderColor = primary)
```
```kotlin
TextField(value = text, onValueChange = { text = it }, label = "接口地址", useLabelAsPlaceholder = true,
    singleLine = true, leadingIcon = { Icon(MiuixIcons.Link, null) },
    trailingIcon = { if (text.isNotEmpty()) IconButton({ text = "" }) { Icon(MiuixIcons.Clear, "清除") } },
    modifier = Modifier.fillMaxWidth())
```
`label` floats up when focused/non-empty; `useLabelAsPlaceholder = true` makes it behave like a hint.

**4.11 Switch / Checkbox**
```kotlin
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.SwitchDefaults
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.CheckboxDefaults
import androidx.compose.foundation.selection.ToggleableState
@Composable fun Switch(checked: Boolean, onCheckedChange: ((Boolean) -> Unit)?,   // nullable callback
    modifier: Modifier = Modifier, colors: SwitchColors = SwitchDefaults.switchColors(), enabled: Boolean = true)
@Composable fun Checkbox(state: ToggleableState, onClick: (() -> Unit)?,           // 🚩 ToggleableState, not Boolean
    modifier: Modifier = Modifier, colors: CheckboxColors = CheckboxDefaults.checkboxColors(), enabled: Boolean = true)
```
```kotlin
Switch(checked = on, onCheckedChange = { on = it })
Checkbox(state = ToggleableState(checked), onClick = { checked = !checked })
```

**4.12 IconButton / Icon / FAB / FloatingToolbar**
```kotlin
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.IconButtonDefaults        // MinWidth/MinHeight 40.dp
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.FloatingActionButton
import top.yukonga.miuix.kmp.basic.FloatingActionButtonDefaults // MinWidth/MinHeight 60.dp, ShadowElevation 4.dp
import top.yukonga.miuix.kmp.basic.FloatingToolbar
import top.yukonga.miuix.kmp.basic.FloatingToolbarDefaults   // CornerRadius 50.dp, color surfaceContainer
@Composable fun IconButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    holdDownState: Boolean = false, backgroundColor: Color = Color.Unspecified,
    cornerRadius: Dp = IconButtonDefaults.CornerRadius, minHeight: Dp = …, minWidth: Dp = …,
    content: @Composable () -> Unit)
@Composable fun Icon(imageVector: ImageVector, contentDescription: String?, modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current)
@Composable fun Icon(painter: Painter, contentDescription: String?, modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current)     // + ImageBitmap and ColorProducer overloads
@Composable fun FloatingActionButton(onClick: () -> Unit, modifier: Modifier = Modifier, shape: Shape = CircleShape,
    containerColor: Color = MiuixTheme.colorScheme.primary,
    shadowElevation: Dp = FloatingActionButtonDefaults.ShadowElevation,
    minWidth: Dp = …, minHeight: Dp = …, content: @Composable () -> Unit)
@Composable fun FloatingToolbar(modifier: Modifier = Modifier, color: Color = FloatingToolbarDefaults.defaultColor(),
    cornerRadius: Dp = FloatingToolbarDefaults.CornerRadius,
    outSidePadding: PaddingValues = FloatingToolbarDefaults.OutSidePadding,
    shadowElevation: Dp = 4.dp, showDivider: Boolean = false, content: @Composable () -> Unit)
```
**No `ExtendedFloatingActionButton`.** For the "add music" labelled button use either a circular FAB with
`Icon(MiuixIcons.Add, …)`, or `Scaffold(floatingToolbar = { FloatingToolbar { Row { Icon(…); Text("添加音乐") } } })`.
`Icon` accepts `ImageVector` **and** `Painter`, so existing `painterResource(R.drawable.ic_*)` keeps working.

**4.13 Divider**
```kotlin
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.VerticalDivider
import top.yukonga.miuix.kmp.basic.DividerDefaults   // Thickness 0.75.dp, DividerColor = colorScheme.dividerLine
@Composable fun HorizontalDivider(modifier: Modifier = Modifier, thickness: Dp = DividerDefaults.Thickness,
    color: Color = DividerDefaults.DividerColor)
```
There is no `Divider()` — only `HorizontalDivider` / `VerticalDivider`.

**4.14 Snackbar**
```kotlin
import top.yukonga.miuix.kmp.basic.SnackbarHost
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import top.yukonga.miuix.kmp.basic.Snackbar
import top.yukonga.miuix.kmp.basic.SnackbarDuration   // Short | Long | Indefinite | Custom(durationMillis)
import top.yukonga.miuix.kmp.basic.SnackbarResult     // Dismissed | ActionPerformed
class SnackbarHostState {
    suspend fun showSnackbar(message: String, actionLabel: String? = null, withDismissAction: Boolean = false,
        duration: SnackbarDuration = SnackbarDuration.Short): SnackbarResult
    suspend fun dismiss(); suspend fun performAction(); suspend fun clear()
    suspend fun newestSnackbarData(): SnackbarData?; suspend fun oldestSnackbarData(): SnackbarData?
}
@Composable fun SnackbarHost(state: SnackbarHostState, modifier: Modifier = Modifier,
    canSwipeToDismiss: Boolean = true, content: @Composable (SnackbarData) -> Unit = { Snackbar(it) })
@Composable fun Snackbar(data: SnackbarData, modifier: Modifier = Modifier,
    cornerRadius: Dp = SnackbarDefaults.CornerRadius /*16.dp*/,
    colors: SnackbarColors = SnackbarDefaults.snackbarColors(),
    insideMargin: PaddingValues = SnackbarDefaults.InsideMargin /*12.dp*/)
```
Miuix **does** ship a `SnackbarHost` (older docs say otherwise). Host it in the Scaffold:
```kotlin
val snackbar = remember { SnackbarHostState() }; val scope = rememberCoroutineScope()
Scaffold(snackbarHost = { SnackbarHost(state = snackbar) }) { padding -> … }
scope.launch { snackbar.showSnackbar("已保存", duration = SnackbarDuration.Short) }
```
The host renders in the Scaffold overlay above `bottomBar` and is swipe-to-dismissable.

**4.15 Bottom sheets — prefer `OverlayBottomSheet`**
```kotlin
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet     // in-Scaffold overlay  ✅ recommended
import top.yukonga.miuix.kmp.window.WindowBottomSheet       // real platform Dialog window
import top.yukonga.miuix.kmp.layout.BottomSheetDefaults     // NOTE: .layout, not .overlay/.window
// backgroundColor() = background, dragHandleColor() = onSurfaceVariantSummary @ 20%, cornerRadius 28.dp,
// maxWidth 640.dp, outsideMargin DpSize(0.dp, 0.dp), insideMargin DpSize(24.dp, 0.dp)
@Composable fun OverlayBottomSheet(show: Boolean, modifier: Modifier = Modifier, title: String? = null,
    startAction: @Composable (() -> Unit)? = null, endAction: @Composable (() -> Unit)? = null,
    backgroundColor: Color = BottomSheetDefaults.backgroundColor(), enableWindowDim: Boolean = true,
    cornerRadius: Dp = BottomSheetDefaults.cornerRadius, sheetMaxWidth: Dp = BottomSheetDefaults.maxWidth,
    onDismissRequest: (() -> Unit)? = null, onDismissFinished: (() -> Unit)? = null,
    outsideMargin: DpSize = BottomSheetDefaults.outsideMargin, insideMargin: DpSize = BottomSheetDefaults.insideMargin,
    defaultWindowInsetsPadding: Boolean = true, dragHandleColor: Color = BottomSheetDefaults.dragHandleColor(),
    allowDismiss: Boolean = true, enableNestedScroll: Boolean = true,
    renderInRootScaffold: Boolean = true, content: @Composable () -> Unit)
// WindowBottomSheet: identical params, WITHOUT renderInRootScaffold
```
```kotlin
Scaffold { padding ->
    Box(Modifier.padding(padding)) {
        Button(onClick = { showSheet = true }) { Text("匹配") }
        OverlayBottomSheet(show = showSheet, title = "在线匹配",
            startAction = { IconButton({ showSheet = false }) { Icon(MiuixIcons.Close, "关闭") } },
            endAction = { IconButton({ applyMatch() }) { Icon(MiuixIcons.Ok, "应用") } },
            onDismissRequest = { showSheet = false }) { Card { /* rows */ } }
    }
}
```
It renders through the ancestor `Scaffold`'s popup host — **a `Scaffold` must be above it**, otherwise
nothing appears. `renderInRootScaffold = true` (default) escapes nested Scaffolds and covers the screen.

**4.16 Dialog — prefer `OverlayDialog`**
```kotlin
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.window.WindowDialog
import top.yukonga.miuix.kmp.layout.DialogDefaults   // titleColor()=onBackground, summaryColor()=onSurfaceSecondary,
// backgroundColor()=background, MaxWidth 420.dp, CornerRadius 32.dp,
// outsideMargin DpSize(12.dp,12.dp), insideMargin DpSize(24.dp,24.dp)
@Composable fun OverlayDialog(show: Boolean, modifier: Modifier = Modifier, title: String? = null,
    titleColor: Color = DialogDefaults.titleColor(), summary: String? = null,
    summaryColor: Color = DialogDefaults.summaryColor(), backgroundColor: Color = DialogDefaults.backgroundColor(),
    enableWindowDim: Boolean = true, onDismissRequest: (() -> Unit)? = null,
    onDismissFinished: (() -> Unit)? = null, outsideMargin: DpSize = DialogDefaults.outsideMargin,
    insideMargin: DpSize = DialogDefaults.insideMargin, defaultWindowInsetsPadding: Boolean = true,
    renderInRootScaffold: Boolean = true, maxWidth: Dp = DialogDefaults.MaxWidth,
    largeScreen: Boolean? = null, cornerRadius: Dp? = null, content: @Composable () -> Unit)
// WindowDialog: same params WITHOUT renderInRootScaffold
```
```kotlin
OverlayDialog(show = showDelete, title = "删除曲目", summary = "此操作不可撤销。",
    onDismissRequest = { showDelete = false }) {
    TextButton("删除", { remove(); showDelete = false }, modifier = Modifier.fillMaxWidth())
    TextButton("取消", { showDelete = false }, modifier = Modifier.fillMaxWidth())
}
```
`content` is a plain `@Composable () -> Unit` (no Column scope) — supply your own `Column`/`Row`.

**4.17 PullToRefresh**
```kotlin
import top.yukonga.miuix.kmp.basic.PullToRefresh
import top.yukonga.miuix.kmp.basic.rememberPullToRefreshState
import top.yukonga.miuix.kmp.utils.overScrollVertical
@Composable fun PullToRefresh(isRefreshing: Boolean, onRefresh: () -> Unit, modifier: Modifier = Modifier,
    pullToRefreshState: PullToRefreshState = rememberPullToRefreshState(),
    contentPadding: PaddingValues = PaddingValues(0.dp), topAppBarScrollBehavior: ScrollBehavior? = null,
    color: Color = PullToRefreshDefaults.color, circleSize: Dp = PullToRefreshDefaults.circleSize,
    refreshTexts: List<String> = PullToRefreshDefaults.refreshTexts,
    refreshTextStyle: TextStyle = PullToRefreshDefaults.refreshTextStyle,
    onPullProgress: ((Float) -> Unit)? = null, content: @Composable () -> Unit)
```
```kotlin
PullToRefresh(isRefreshing = refreshing, onRefresh = { refreshing = true }) {
    LazyColumn(Modifier.fillMaxSize().overScrollVertical(), overscrollEffect = null) { … }
}
```
For Miuix's rubber-band overscroll, scrollables combine `Modifier.overScrollVertical()` /
`overScrollHorizontal()` **with** `overscrollEffect = null`.

**4.18 Preferences (module `miuix-preference`)**
```kotlin
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.CheckboxPreference
import top.yukonga.miuix.kmp.preference.CheckboxLocation   // Start (default) | End
@Composable fun SwitchPreference(checked: Boolean, onCheckedChange: (Boolean) -> Unit, title: String,
    modifier: Modifier = Modifier, titleColor: BasicComponentColors = BasicComponentDefaults.titleColor(),
    summary: String? = null, summaryColor: BasicComponentColors = BasicComponentDefaults.summaryColor(),
    startAction: @Composable (() -> Unit)? = null, endActions: @Composable RowScope.() -> Unit = {},
    bottomAction: (@Composable () -> Unit)? = null, switchColors: SwitchColors = SwitchDefaults.switchColors(),
    insideMargin: PaddingValues = BasicComponentDefaults.InsideMargin, holdDownState: Boolean = false,
    enabled: Boolean = true)
@Composable fun ArrowPreference(title: String, modifier: Modifier = Modifier,
    titleColor: BasicComponentColors = …, summary: String? = null, summaryColor: BasicComponentColors = …,
    startAction: @Composable (() -> Unit)? = null, endActions: @Composable RowScope.() -> Unit = {},
    bottomAction: (@Composable () -> Unit)? = null, insideMargin: PaddingValues = …,
    onClick: (() -> Unit)? = null, holdDownState: Boolean = false, enabled: Boolean = true)
@Composable fun CheckboxPreference(title: String, checked: Boolean, onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier, titleColor: BasicComponentColors = …, summary: String? = null,
    summaryColor: BasicComponentColors = …, checkboxColors: CheckboxColors = CheckboxDefaults.checkboxColors(),
    startAction: @Composable (() -> Unit)? = null, endActions: @Composable (RowScope.() -> Unit)? = null,
    checkboxLocation: CheckboxLocation = CheckboxLocation.Start, bottomAction: (@Composable () -> Unit)? = null,
    insideMargin: PaddingValues = …, holdDownState: Boolean = false, enabled: Boolean = true)
```
Put rows inside `Card { }`. `BasicComponentDefaults` / `BasicComponentColors` come from
`top.yukonga.miuix.kmp.basic`. Also available: `RadioButtonPreference`, `SliderPreference`,
dropdown/spinner preferences (overlay + window variants).

**4.19 Progress**
```kotlin
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.ProgressIndicatorDefaults // linear 6.dp, circular stroke 4.dp / size 30.dp
@Composable fun CircularProgressIndicator(modifier: Modifier = Modifier, progress: Float? = null,
    colors: ProgressIndicatorColors = ProgressIndicatorDefaults.progressIndicatorColors(),
    strokeWidth: Dp = 4.dp, size: Dp = 30.dp)
@Composable fun LinearProgressIndicator(modifier: Modifier = Modifier, progress: Float? = null,
    colors: ProgressIndicatorColors = …, height: Dp = 6.dp)
// colors: foreground = primary, background = secondaryContainer, disabled = disabledPrimarySlider
// progress == null => indeterminate
```

**4.20 TabRow (segmented control)**
```kotlin
import top.yukonga.miuix.kmp.basic.TabRow
import top.yukonga.miuix.kmp.basic.TabRowWithContour
import top.yukonga.miuix.kmp.basic.TabRowDefaults // height 42.dp, corner 12.dp (Contour: 45.dp / 8.dp)
@Composable fun TabRow(tabs: List<String>, selectedTabIndex: Int, onTabSelected: (Int) -> Unit,
    modifier: Modifier = Modifier, colors: TabRowColors = TabRowDefaults.tabRowColors(),
    minWidth: Dp = …, maxWidth: Dp = …, height: Dp = …, cornerRadius: Dp = …, itemSpacing: Dp = 9.dp,
    contentAlignment: Alignment = Alignment.Center, listState: LazyListState? = null,
    interactionSource: MutableInteractionSource? = null, indication: Indication? = null)
```
```kotlin
var idx by remember { mutableIntStateOf(0) }
TabRow(listOf("专辑", "歌词", "在线"), idx, { idx = it })
```

## 5. Icons (`miuix-icons` 0.9.4)

```kotlin
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back      // one import PER icon name
Icon(imageVector = MiuixIcons.Back, contentDescription = "返回")
```
Each icon is an **extension property** `val MiuixIcons.Back: ImageVector` declared in
`top.yukonga.miuix.kmp.icon.extended`, so every name needs its own import. Weight variants:
`MiuixIcons.X` == `MiuixIcons.Regular.X`; also `.Light.X`, `.Normal.X`, `.Medium.X`, `.Demibold.X`.
A tiny basic set lives in `top.yukonga.miuix.kmp.icon.basic`: `ArrowRight`, `ArrowUpDown`, `Check`,
`Close`, `Search`, `SearchCleanup`, `Sidebar`. ⚠ `Close`/`Search` exist in **both** packages — importing
both is a conflicting-import error, so prefer the `extended` ones.

| Need | Icon name |
|---|---|
| back | `Back` (also `ChevronBackward`) |
| add | `Add`, `AddCircle`, `AddFolder` |
| more / overflow | `More`, `MoreCircle` |
| search | `Search` (extended) |
| refresh | `Refresh` (also `Update`, `Reset`) |
| settings | `Settings` (also `Tune`, `Theme`) |
| delete | `Delete` |
| edit | `Edit` (also `Create`, `Rename`) |
| check | `Ok` (extended) / `MiuixIcons.Basic.Check` |
| close | `Close`, `Close2`, `Clear` |
| info | `Info` |
| warning | ❌ **none** (no `Warning`/`Alert`/`Error`) → `Icons.Filled.Warning` or a vector drawable |
| arrow down / up / right | `ExpandMore` / `ExpandLess` / `Forward` + `MiuixIcons.Basic.ArrowRight`; `MiuixIcons.Basic.ArrowUpDown`; `ChevronForward`/`ChevronBackward` |
| music / audio | `Music`, `Playlist`, `Album`, `Mic`, `VolumeUp`, `Play`, `Pause`, `Recording` |
| album / image | `Album`, `Image`, `Photos`, `MapAlbum`, `Background` |
| cloud / download | `Download`, `CloudFill`, `UploadCloud`, `FileDownloads`, `TopDownloads`, `Backup` |
| tag / label | ❌ **none** (no `Tag`/`Label`) → closest `Promotions`, `Notes`, else vector drawable |
| folder | `Folder`, `FolderFill`, `AddFolder`, `MoveFile` |
| palette / color | ❌ **none** (no `Palette`) → closest `Theme`, `Background`, else vector drawable |
| lyrics / text | `Notes`, `NotesFill` |
| sparkle / star | ❌ no `Sparkle` → `Favorites` / `FavoritesFill` (star) |

**Full extended set (156):** `Add, AddCircle, AddFolder, Alarm, Album, All, Answer, AppRecording, Back,
Background, Backup, BankCards, Blocklist, CallRecording, Carrier, ChevronBackward, ChevronForward, Clear,
Close, Close2, CloudFill, Community, Contacts, ContactsBook, ContactsCircle, ConvertFile, Copy, Create, Cut,
Delete, Download, Edit, Email, ExpandLess, ExpandMore, Favorites, FavoritesFill, File, FileDownloads, Filter,
Folder, FolderFill, Forward, GridView, Help, Hide, Home, HorizontalSplit, Image, Import, Info, Layers, Link,
ListView, Location, Lock, MapAlbum, Merge, Messages, Mic, MicSlash, MindMap, Months, More, MoreCircle,
MoveFile, Music, Notes, NotesFill, Ok, Paste, Pause, Phone, Photos, Pin, Play, Playlist, Promotions, Recent,
Recording, RecordingTape, Redo, Refresh, Remove, RemoveContact, Rename, Replace, Reply, ReplyAll, Report,
Reset, RotateLeft, Scan, ScreenCapture, ScreenMirroring, Search, SearchDevice, SelectAll, Send, Settings,
Share, Show, Sidebar, Sort, Stopwatch, Store, Tasks, Th1–Th31, Theme, Timer, TopDownloads, Translate, Trim,
Tune, Undo, Unlock, Unpin, Update, UploadCloud, VerticalSplit, VolumeOff, VolumeUp, Weeks, WorldClock, Years,
ZoomOut`

## 6. Gotchas

1. **`Scaffold` is the overlay host.** `OverlayDialog` / `OverlayBottomSheet` / dropdowns draw through
   CompositionLocals supplied by the nearest `Scaffold` (`popupHost`). Without a Scaffold ancestor they
   silently render nothing. The editor screen in `MusicTagApp` is drawn in a `Box` *above* the Scaffold —
   it (and `OnlineMatchSheet`) needs its **own** `Scaffold` wrapper.
2. Never override `popupHost`; the default `MiuixPopupHost()` is required for overlays.
3. **Insets**: Scaffold only feeds its `PaddingValues` from `contentWindowInsets` (systemBars ∪
   displayCutout) when the matching bar slot is empty; bars self-pad (`defaultWindowInsetsPadding = true`).
   Passing `WindowInsets(0,0,0,0)` (as the app does today) means manual padding.
4. **No experimental opt-ins** for anything above. The only opt-in in the library is
   `top.yukonga.miuix.kmp.interfaces.ExperimentalScrollBarApi` (WARNING level, `ScrollBar` only).
5. `MiuixTheme(colors = …)` does **not** provide `LocalContentColor` — use the `controller` overload or
   wrap content in `Surface`.
6. `MiuixTheme` must be present: components read `LocalColors`/`LocalTextStyles` (static locals defaulting
   to the light scheme), so outside the theme they compile but render light-only.
7. **Material 3 interop**: M3 `Text`/`Icon`/`Card`/`Button` still work if imported explicitly, but they use
   M3 defaults (purple), not HyperOS colors. Prefer a full pivot; don't expect Miuix colors inside
   `MaterialTheme`.
8. **Name collisions** (`Text`, `Icon`, `Card`, `Button`, `Switch`, `Checkbox`, `Scaffold`, `NavigationBar`,
   `TopAppBar`, `Surface`, `TabRow`, `FloatingActionButton`) — delete M3 imports or alias them.
9. Signature traps: `TextButton(text: String, …)` (no content lambda) · `Checkbox(state = ToggleableState(…))`
   · `Switch.onCheckedChange` nullable · `FloatingActionButton` is circular only (no Extended FAB) ·
   `TextField` has `label`/`useLabelAsPlaceholder` (**no** `placeholder`) and `cornerRadius` (**no** `shape`).
10. `Card` uses `insideMargin: PaddingValues` (default `0.dp`) + `cornerRadius`, **not** `elevation`.
11. Scrolling: pair `Modifier.overScrollVertical()` with `overscrollEffect = null` for the HyperOS bounce.
12. Icons must be imported one-by-one, and `Close`/`Search` are ambiguous between `basic` and `extended`.
13. `PullToRefresh(topAppBarScrollBehavior = …)` lets the app bar collapse while pulling.

## Minimal screen skeleton (copy-paste)

```kotlin
@Composable
fun LibraryScreen(state: LibraryState, onOpen: (String) -> Unit, onRefresh: () -> Unit) {
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val scroll = MiuixScrollBehavior()
    var refreshing by remember { mutableStateOf(false) }
    Scaffold(
        topBar = {
            SmallTopAppBar(title = "曲库", subtitle = "${state.tracks.size} 首", scrollBehavior = scroll,
                actions = { IconButton({ scope.launch { snackbar.showSnackbar("已刷新") } }) {
                    Icon(MiuixIcons.Refresh, "刷新") } })
        },
        snackbarHost = { SnackbarHost(state = snackbar) },
    ) { padding ->
        PullToRefresh(isRefreshing = refreshing, onRefresh = { refreshing = true },
            topAppBarScrollBehavior = scroll, modifier = Modifier.padding(padding)) {
            LazyColumn(Modifier.fillMaxSize().overScrollVertical(), overscrollEffect = null,
                contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(state.tracks, key = { it.uri }) { track ->
                    Card { BasicComponent(title = track.title, summary = track.artist,
                        onClick = { onOpen(track.uri) }) }
                }
            }
        }
    }
}
```
