# 计划：菜单弹出改为「按钮正上方锚定」+ 紧凑白底黑字设计

## 需求（用户提出）

1. 菜单不再屏幕居中，而是出现在**被点击的菜单按钮正上方**
2. 圆角、白底、黑字的设计（在现有基础上精修）
3. 菜单项太大，改小

## 现状分析

- [BarMenu.java](file:///c:/Users/Kano/Documents/ChatGPT/WSL环境/imebar/app/src/main/java/com/local/imebar/BarMenu.java)：卡片用 `cardParams.gravity = Gravity.CENTER` 居中；条目 16sp 文字 + 上下 14dp / 左右 20dp 内边距（条目高约 48dp，偏大）；卡片圆角 16dp、minWidth 196dp
- [ImeBar.java](file:///c:/Users/Kano/Documents/ChatGPT/WSL环境/imebar/app/src/main/java/com/local/imebar/ImeBar.java#L296-L313)：按钮点击处拿到被点的 View `v`，但调用 `BarMenu.toggle((ViewGroup) decor, context, service, button)` 时**没有把 `v` 传下去** —— 锚定需要它
- 覆盖层（scrim）是 MATCH_PARENT 铺满 DecorView，卡片放 scrim 里，所以卡片坐标可以直接用「相对 DecorView」的 margin 定位
- 卡片是 WRAP_CONTENT，添加前不知道尺寸 → 需要**手动 measure** 后再计算位置（不能依赖布局完成后的回调，否则会闪一帧错误位置）

## 修改方案

### 1. BarMenu.java — 锚定定位 + 紧凑样式

**签名改动**（两个方法加 `View anchor` 参数）：

```java
static void toggle(ViewGroup decor, View anchor, Context context,
                   InputMethodService service, BarConfig.Button button)
private static void show(ViewGroup decor, View anchor, Context context,
                         InputMethodService service, BarConfig.Button button)
```

**紧凑设计参数**（圆角白底黑字，整体小一号）：

| 项 | 现状 | 改为 |
|---|---|---|
| 卡片圆角 | 16dp | 12dp |
| 卡片 minWidth | 196dp | 156dp |
| 卡片上下内边距 | 6dp | 4dp |
| 条目文字 | 16sp | 14sp（黑色 `0xFF191C20` 不变） |
| 条目水平内边距 | 20dp | 16dp |
| 条目垂直内边距 | 14dp | 8dp（条目高约 36dp，紧凑但好点） |
| 分隔线两侧缩进 | 18dp | 12dp |
| 涟漪色 | 主色 12%（`0x1F0B57D0`） | 中性黑 12%（`0x1F000000`）——配合纯白底黑字更协调 |
| 阴影 elevation | 3dp | 3dp（不变） |
| scrim 压暗 | `0x14000000` | 不变 |

**定位算法**（替换 `Gravity.CENTER`）：

```java
// 1) 手动测量卡片尺寸（scrim 还没布局，必须先 measure）
int atMost = View.MeasureSpec.makeMeasureSpec(decor.getWidth(), View.MeasureSpec.AT_MOST);
card.measure(atMost, View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
int cardW = Math.max(card.getMeasuredWidth(), card.getMinimumWidth());
int cardH = card.getMeasuredHeight();

// 2) 算锚点按钮在 DecorView 坐标系里的位置
int[] aLoc = new int[2], dLoc = new int[2];
anchor.getLocationInWindow(aLoc);
decor.getLocationInWindow(dLoc);
int anchorCenterX = aLoc[0] - dLoc[0] + anchor.getWidth() / 2;
int anchorTop = aLoc[1] - dLoc[1];
int anchorBottom = anchorTop + anchor.getHeight();

// 3) 卡片底边贴在按钮正上方，留 6dp 间隙
int gap = dp(context, 6);
int x = anchorCenterX - cardW / 2;
int y = anchorTop - cardH - gap;

// 4) 水平方向夹在 DecorView 内（两侧至少留 8dp），防止按钮靠屏幕边时卡片出界
int minX = dp(context, 8);
if (x < minX) x = minX;
if (x + cardW > decor.getWidth() - minX) x = decor.getWidth() - minX - cardW;

// 5) 垂直方向兜底：上方放不下（极端情况）就改到按钮下方
if (y < dp(context, 8)) {
    y = anchorBottom + gap;
}

FrameLayout.LayoutParams cardParams = new FrameLayout.LayoutParams(
        FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
cardParams.gravity = Gravity.TOP | Gravity.START;
cardParams.leftMargin = x;
cardParams.topMargin = y;
```

异常兜底：若 `decor.getWidth()` 为 0 或 `getLocationInWindow` 拿不到有效值，回退到原来的 `Gravity.CENTER` 居中（保证菜单永远能弹出来）。

### 2. ImeBar.java — 把被点的按钮传下去

[ImeBar.java](file:///c:/Users/Kano/Documents/ChatGPT/WSL环境/imebar/app/src/main/java/com/local/imebar/ImeBar.java#L301) 菜单分支一行改动：

```java
BarMenu.toggle((ViewGroup) decor, v, context, service, button);
```

### 3. README.md — 同步两处描述

- 第 73 行附近「带菜单的按钮弹出一张**居中的**浅色圆角卡片」→ 改为「在按钮**正上方**弹出一张白色圆角卡片」
- 第 103 行 MD3 打磨描述同步（圆角 16dp → 12dp、条目更紧凑）

## 假设与决策

- 工具栏贴在键盘底部，按钮上方就是整个键盘区域，菜单在 IME 窗口内部向上弹**不会被窗口裁掉**（scrim 铺满 DecorView，卡片在其内部）
- 菜单文字固定黑色（不跟随用户配置的工具栏文字色）——沿用现状，可读性优先
- 弹出时机仍在触摸事件里同步完成（手动 measure 定位，无布局延迟、不闪帧）
- 不改任何动作执行、日志、dismiss 逻辑

## 验证

1. 推送 GitHub 由 `build-apk` workflow 编译（无本地构建环境）
2. 装机验证点：
   - 点「更多」类菜单按钮，卡片出现在**该按钮正上方**、水平居中对齐按钮、两侧不出屏
   - 靠屏幕左右边缘的按钮点菜单，卡片自动夹回屏内
   - 条目明显变小（约 36dp 高、14sp 字），白底黑字、圆角 12dp、点按有涟漪
   - 点空白处收起、再点同一按钮收起、菜单项动作正常执行（逻辑未动）
