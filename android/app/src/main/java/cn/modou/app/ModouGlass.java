package cn.modou.app;

import android.app.Activity;
import android.content.Context;
import android.graphics.Outline;
import android.graphics.RenderEffect;
import android.graphics.RuntimeShader;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.Window;
import android.view.WindowManager;

import java.util.function.Consumer;

/**
 * 真实时液态玻璃（0.9.138）——系统跨窗口背景模糊 + 材质层 + AGSL 折射。
 *
 * <p><b>为什么不是 View.setRenderEffect</b>：那个 API 是给 View <b>自己</b>的绘制输出加滤镜，
 * 不会去采样它背后的兄弟节点。0.9.137 及以前的做法是 PixelCopy 抓一张背后画面的快照，
 * 铺进 ImageView 再挂 RenderEffect —— 模糊本身在 GPU 上，但输入是一张静态位图，
 * 所以滚动时只能停稳再抓一次，玻璃里永远是「上一次的画面」。
 *
 * <p><b>这里的做法</b>：把玻璃放进一个独立的 translucent + floating 窗口，由 SurfaceFlinger
 * 在每一帧合成时用 {@link Window#setBackgroundBlurRadius(int)} 实时卷积窗口 bounds 内的下层画面。
 * 系统的模糊结果之上会画 window background drawable，正好用它承担 5~10% 遮罩和边缘高光。
 * 手指滑动、封面滚动时下层每帧重绘，模糊自动跟着变 —— 没有抓屏、没有去抖、没有刷新调用。
 *
 * <p><b>层次（自下而上）</b>：Activity 内容 → 系统实时模糊 → 窗口背景（极淡，只定形状）
 * → {@link GlassMaterial} 材质层（0.9.152：65% 通透磨砂底 + 宽柔边缘泛光/极淡内阴影
 * + 窗口 elevation 投影，可选 AGSL 折射色散）→ 图标与文字。
 * <b>边界靠光影，不靠描边线</b>（白细/白粗/暗线/白板几版均已试错作废）。
 *
 * <p><b>能力边界</b>：跨窗口模糊可能被系统临时关掉（省电模式、视频隧道、GPU 限制、
 * 开发者选项）。官方要求用 {@code WindowManager.isCrossWindowBlurEnabled()} 查询并监听开关，
 * 关闭时把遮罩提到接近实底，否则窗口背景全透明、图标文字会直接压在清晰内容上看不清。
 * 这不是「低版本降级分支」——API 31 的机器同样可能关掉，是运行时状态，必须处理。
 *
 * <p><b>AGSL 折射</b>：API 33+ 用 {@link RuntimeShader} 对材质层自身的边缘高光做轻微
 * 径向位移 + R/B 色散，模拟有厚度的玻璃边缘。它采样的是材质层自己的内容（不是背后画面），
 * 因此不触犯「禁止截屏取色」，也不依赖任何窗口外像素。31/32 上跳过这一步，材质层照常绘制。
 */
final class ModouGlass {

    private ModouGlass() {
    }

    /** 背景模糊半径（px）。AOSP 建议磨砂玻璃 ~80px、上限 150px；这里按 dp 换算后夹住。 */
    static final float BLUR_DP = 26f;

    /**
     * 卡片底（0.9.152）：撤销 0.9.151 的 90% 白板，回到「通透磨砂」。
     *
     * <p>0.9.151 把系统菜单误读成「近实白不透明卡片」并据此提实，实拍成一块发闷的暖白板，
     * 边界照样糊（背景也是浅灰白，明度差几乎为零），玻璃质感反而丢了。放大核对后系统菜单
     * （照片 / 备忘录）其实同为<b>半透明磨砂玻璃</b>，背后内容糊成柔和色块仍能透出；
     * 它们清晰的边界来自「宽而柔的白色边缘泛光 + 极淡投影」，不是白底、也不是描边线。
     *
     * <p>这里取 65% / 69%：背后彩色书封经 26dp 系统模糊后只剩柔和色晕、不再能辨字形，
     * 但玻璃仍是通透的磨砂而非死白板。边界交给 {@link ModouGlassDrawable#setEdgeLight}
     * 的光影与窗口投影，窗口层一律不画线。
     */
    // 0.9.159：玻璃基底 / 模糊禁用兜底色 / 边缘高光色全部收口到 DesignSpec。
    // 墨读取值（glassMaterialVeil = 0xA6F7F3EC / 0xB0202024，glassSolidFallback =
    // 0xC8F7F3EC / 0xD0202024）与下面这几个 0.9.152 定稿的旧常量逐位相同 —— 零回归；
    // M3 / iOS 则各自读 surface / glassBase，不会再出现「切了主题还是奶油白玻璃」。
    // RIM_LIGHT / RIM_DARK（0x59FFFFFF / 0x2EFFFFFF）自 0.9.152 改用 setEdgeLight 后
    // 已无引用，随本次收口一并删除，避免留两套边缘高光真值。

    /**
     * 0.9.156：每个挂过模糊的窗口对应的 backdrop drawable。
     * 面板拖动避让（dodgeSheet）要临时让开下半屏正文，必须同时关模糊半径 **和** 窗口底色 ——
     * 光把 radius 设 0 不够：模糊被系统禁用时 applyBlur 会把底色提成 ~78% 实底，那块实白
     * 仍会盖住页脚。恢复时也要按「当前系统开关」重算，不能无条件设回一个固定半径。
     * 统一在这里管，调用方不再直接碰 Window 的模糊参数。弱引用，窗口销毁后自动回收。
     */
    private static final java.util.WeakHashMap<Window, GradientDrawable> BACKDROPS =
            new java.util.WeakHashMap<>();

    static boolean isDark(Context c) {
        return ModouUi.isDark(c);
    }

    static int blurRadiusPx(Context c) {
        float px = BLUR_DP * c.getResources().getDisplayMetrics().density;
        return (int) Math.min(150f, Math.max(1f, px));
    }

    /**
     * 窗口背景 drawable：系统用它推断模糊区域的 bounds 与圆角，并把它画在模糊结果之上。
     * 颜色保持极低不透明度，真正那层 5~10% 遮罩交给 {@link GlassMaterial}，避免叠加两次变白板。
     */
    static GradientDrawable windowBackdrop(Context c, float cornerPx) {
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(cornerPx);
        g.setColor(isDark(c) ? 0x08000000 : 0x08FFFFFF);
        // 0.9.152：窗口层**仍然不画任何描边线**。描边这条路几版已全部试错——
        //   白细线→白对白看不见、白粗线→生硬硬框、暗细线→黑线、提实白板→死板发闷。
        // 边界改由两样「光影」承担（都落在材质层 / 窗口级，不在窗口背景上画线）：
        //   ① ModouGlassDrawable.setEdgeLight：宽而柔的白色内泛光 + 极淡内阴影；
        //   ② Theme.Modou.Glass 的 android:windowElevation：系统在窗口外画的极淡投影。
        // 这里只保留极淡底色 + 圆角，供系统推断模糊区域的圆角形状。
        return g;
    }

    /**
     * 给一个已经 show 出来的 translucent + floating 窗口挂上实时背景模糊。
     * 必须在 {@code Dialog.show()} / {@code Window.setLayout()} 之后调用。
     *
     * @param cornerPx 圆角半径（px），胶囊传短边一半
     */
    static void attach(Window w, float cornerPx) {
        if (w == null) return;
        Context c = w.getContext();
        final GradientDrawable bg = windowBackdrop(c, cornerPx);
        w.setBackgroundDrawable(bg);
        BACKDROPS.put(w, bg);
        // dim / 触摸 / 焦点标志一律交给调用方：常驻底栏要 NOT_TOUCH_MODAL 让书架还能滚，
        // 模态面板却要保留 dim 与点外关闭，两者正好相反，不能在这里一刀切。

        // 监听运行时开关：省电模式 / 隧道播放时系统会把跨窗口模糊整个关掉。
        // 关掉后窗口背景会变得完全透明，必须把遮罩提亮，否则图标文字压在清晰封面上不可读。
        final Consumer<Boolean> listener = enabled -> applyBlur(w, bg, enabled);
        final WindowManager wm = (WindowManager) c.getSystemService(Context.WINDOW_SERVICE);
        if (wm != null) {
            try {
                wm.addCrossWindowBlurEnabledListener(c.getMainExecutor(), listener);
                View decor = w.getDecorView();
                decor.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
                    @Override
                    public void onViewAttachedToWindow(View v) {
                    }

                    @Override
                    public void onViewDetachedFromWindow(View v) {
                        try {
                            wm.removeCrossWindowBlurEnabledListener(listener);
                        } catch (Throwable ignore) {
                        }
                        v.removeOnAttachStateChangeListener(this);
                    }
                });
            } catch (Throwable ignore) {
                // 个别 ROM 的 WindowManager 没有这组回调：退回一次性查询
            }
        }
        applyBlur(w, bg, crossWindowBlurEnabled(wm));
    }

    private static void applyBlur(Window w, GradientDrawable bg, boolean enabled) {
        Context c = w.getContext();
        boolean dark = isDark(c);
        try {
            w.setBackgroundBlurRadius(enabled ? blurRadiusPx(c) : 0);
            // 0.9.159：禁用态的实底兜底改读规格（墨读取值与旧 SOLID_* 一致，零回归）。
            bg.setColor(enabled
                    ? (dark ? 0x08000000 : 0x08FFFFFF)
                    : DesignSpec.of(c).glassSolidFallback);
            bg.invalidateSelf();
        } catch (Throwable ignore) {
            // 极少数 ROM 拒绝大核模糊：保持遮罩现状，至少不会崩
        }
    }

    /**
     * 临时让开窗口背后那一块画面（阅读页拖滑杆时的面板避让）。
     * 关模糊半径的同时把窗口底色置为全透明 —— 否则「模糊被系统禁用」时 applyBlur 提亮的
     * 那层实底会实打实盖住页脚，避让等于没做。窗口本身保持可见，不打断滑杆的触摸序列。
     */
    static void suspendBlur(Window w) {
        if (w == null) return;
        try {
            w.setBackgroundBlurRadius(0);
            GradientDrawable bg = BACKDROPS.get(w);
            if (bg != null) {
                bg.setColor(0x00000000);
                bg.invalidateSelf();
            }
        } catch (Throwable ignore) {
            // 个别 ROM 拒绝设 radius：底色透明仍在，最差是少糊一层
        }
    }

    /** 恢复：按当前系统跨窗口模糊开关，走 applyBlur 同一套判定把 radius 与底色一起复原。 */
    static void resumeBlur(Window w) {
        if (w == null) return;
        GradientDrawable bg = BACKDROPS.get(w);
        if (bg == null) {
            // 没登记过（老窗口 / 极端时序）：退回旧的固定半径恢复，至少不更差
            try {
                w.setBackgroundBlurRadius(crossWindowBlurEnabled(
                        (WindowManager) w.getContext().getSystemService(Context.WINDOW_SERVICE))
                        ? blurRadiusPx(w.getContext()) : 0);
            } catch (Throwable ignore) {
            }
            return;
        }
        WindowManager wm = (WindowManager) w.getContext().getSystemService(Context.WINDOW_SERVICE);
        applyBlur(w, bg, crossWindowBlurEnabled(wm));
    }

    /**
     * 跨窗口模糊开关是 WindowManager 的<b>实例</b>方法（不是静态的），且只在运行时有效：
     * 省电模式、视频隧道、GPU 限制、开发者选项都可能把它关掉，与 API 版本无关。
     */
    static boolean crossWindowBlurEnabled(WindowManager wm) {
        try {
            return wm == null || wm.isCrossWindowBlurEnabled();
        } catch (Throwable ignore) {
            return true;   // 查不到就按可用处理，最差是多糊一层
        }
    }

    /** 建一层玻璃材质。返回的是一个透明容器 View，调用方把图标文字盖在它上面。 */
    static GlassMaterial material(Context c, float cornerDp) {
        return new GlassMaterial(c, cornerDp, false);
    }

    /**
     * 底栏专用：凸透镜式液态玻璃（中心透明、边缘厚白、彩色光晕、外扩高光）。
     * 与 {@link #material} 的区别见 {@link ModouLiquidGlass}；AGSL 折射同样挂上。
     */
    static GlassMaterial dockMaterial(Context c, float cornerDp) {
        return new GlassMaterial(c, cornerDp, true);
    }

    /**
     * 材质层（0.9.152）：65%/69% 通透磨砂底 + 宽柔白色边缘泛光 + 极淡内阴影。
     * 边界由 {@link ModouGlassDrawable#setEdgeLight} 的光影与窗口投影承担，不再画细描边线。
     * API 33+ 额外挂 AGSL 折射，让边缘有轻微色散与厚度感。
     */
    static final class GlassMaterial extends View {

        /** 普通面板材质（sheet / 菜单 / 圆钮）；底栏为 null */
        private ModouGlassDrawable drawable;
        /** 底栏凸透镜材质；普通面板为 null */
        private ModouLiquidGlass liquid;
        /** 只在 API 33+ 上实例化；用 Object 存，低版本加载本类时不会去解析 RuntimeShader */
        private Object refraction;

        GlassMaterial(Context c, float cornerDp, boolean liquidGlass) {
            super(c);
            boolean dark = isDark(c);
            if (liquidGlass) {
                // 底栏不再整块平涂白遮罩：中心保持透明，边缘才有厚度，背后画面透得出来。
                liquid = new ModouLiquidGlass(ModouUi.dp(c, cornerDp), dark,
                        c.getResources().getDisplayMetrics().density);
                setBackground(liquid);
            } else {
                // 0.9.159：玻璃基底收口到规格（墨读取值与旧 VEIL_* 一致，零回归；
                // M3 / iOS 用各自 surface / glassBase，不再是奶油白）。
                drawable = new ModouGlassDrawable(
                        DesignSpec.of(c).glassMaterialVeil,
                        ModouUi.dp(c, cornerDp), false);
                drawable.setRimWidth(Math.max(1f, ModouUi.dp(c, 1.4f)));
                // 0.9.152：所有弹出层（级联菜单 / 换源 / 规则 / 设置等 sheet）统一开
                // 「宽柔边缘泛光 + 极淡内阴影」—— 边界靠光影立起来，不再靠描边线或白板。
                // 这一处覆盖所有走 ModouFrost.wrap 的窗口；底栏走 dockMaterial 液态玻璃不受影响。
                drawable.setEdgeLight(ModouUi.dp(c, 7f));
                setBackground(drawable);
            }
            // 圆角轮廓：胶囊（cornerDp >= 999）用短边一半，其余按传入值
            setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View v, Outline o) {
                    int w = v.getWidth(), h = v.getHeight();
                    if (w <= 0 || h <= 0) return;
                    float r = cornerDp >= 999f ? Math.min(w, h) * 0.5f
                            : ModouUi.dp(getContext(), cornerDp);
                    o.setRoundRect(0, 0, w, h, r);
                }
            });
            setClipToOutline(true);
            installRefraction();
        }

        private void installRefraction() {
            if (Build.VERSION.SDK_INT < 33) return;   // RuntimeShader 是 API 33
            try {
                refraction = Api33.install(this);
                post(this::syncUniforms);
            } catch (Throwable ignore) {
                refraction = null;   // AGSL 编译失败：材质层照常画，只是没有色散
                try {
                    setRenderEffect(null);
                } catch (Throwable ignore2) {
                }
            }
        }

        @Override
        protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            super.onSizeChanged(w, h, oldw, oldh);
            syncUniforms();
        }

        private void syncUniforms() {
            if (refraction == null || getWidth() <= 0 || getHeight() <= 0) return;
            float d = getResources().getDisplayMetrics().density;
            Api33.uniforms(refraction, getWidth(), getHeight(), d);
        }

        /** 深浅主题切换时重算遮罩与边光颜色 */
        void refreshTheme() {
            boolean dark = isDark(getContext());
            if (liquid != null) {
                liquid.setDark(dark);
            } else if (drawable != null) {
                drawable.setBaseColor(DesignSpec.of(getContext()).glassMaterialVeil);
                drawable.invalidateSelf();
            }
            invalidate();
        }
    }

    /**
     * API 33+ 的 AGSL 折射，单独关在一个类里：31/32 设备加载 ModouGlass 时不会去解析
     * RuntimeShader / RenderEffect.createRuntimeShaderEffect 这些不存在的类型。
     */
    private static final class Api33 {

        /**
         * 边缘折射 / 色散。输入 {@code content} 由 RenderEffect 自动绑定为材质层自身的绘制结果，
         * 不读取窗口外任何像素。靠圆角 SDF 算出「离边缘多近」，越靠边位移越大，R/B 通道反向取样
         * 形成一丝彩边；再叠一层顶亮底消的冷白光晕，玻璃就有厚度而不只是描边。
         */
        private static final String AGSL =
                "uniform shader content;\n"
                        + "uniform float2 size;\n"
                        + "uniform float radius;\n"
                        + "uniform float edge;\n"
                        + "uniform float strength;\n"
                        + "\n"
                        + "float sdRoundBox(float2 p, float2 b, float r) {\n"
                        + "    float2 q = abs(p) - b + float2(r);\n"
                        + "    return min(max(q.x, q.y), 0.0) + length(max(q, float2(0.0))) - r;\n"
                        + "}\n"
                        + "\n"
                        + "half4 main(float2 coord) {\n"
                        + "    float2 p = coord - size * 0.5;\n"
                        + "    float d = sdRoundBox(p, size * 0.5 - float2(0.5), radius);\n"
                        + "    float rim = 1.0 - smoothstep(-edge, 0.0, d);\n"
                        + "    float2 n = p / (length(p) + 0.0001);\n"
                        + "    float2 off = n * rim * strength;\n"
                        + "    half4 c  = content.eval(coord);\n"
                        + "    half4 cr = content.eval(coord + off);\n"
                        + "    half4 cb = content.eval(coord - off);\n"
                        + "    float top = clamp(0.5 - p.y / (size.y + 0.0001), 0.0, 1.0);\n"
                        + "    float glow = rim * (0.03 + 0.09 * top);\n"
                        + "    half3 rgb = half3(cr.r, c.g, cb.b)\n"
                        + "              + half3(glow * 0.90, glow * 0.96, glow);\n"
                        + "    float a = max(c.a, max(cr.a, cb.a));\n"
                        + "    return half4(rgb, a);\n"
                        + "}\n";

        static RuntimeShader install(View v) {
            // 单参构造 = isOpaque(false)，材质层有透明区域，不能用不透明 shader。
            RuntimeShader s = new RuntimeShader(AGSL);
            // 第二个参数必须等于 AGSL 里声明的 `uniform shader content`：
            // RenderEffect 把 View 自身的绘制结果绑定到这个 uniform 上。
            v.setRenderEffect(RenderEffect.createRuntimeShaderEffect(s, "content"));
            return s;
        }

        static void uniforms(Object holder, int w, int h, float density) {
            if (!(holder instanceof RuntimeShader)) return;
            RuntimeShader s = (RuntimeShader) holder;
            try {
                s.setFloatUniform("size", (float) w, (float) h);
                s.setFloatUniform("radius", Math.min(w, h) * 0.5f);
                s.setFloatUniform("edge", 3.5f * density);
                s.setFloatUniform("strength", 1.8f * density);
            } catch (Throwable ignore) {
            }
        }
    }

    /** 供 Activity 判断要不要把底栏放进独立窗口（低配 / 模糊被禁用时也不退回抓屏） */
    static boolean available(Activity a) {
        return a != null && !a.isFinishing() && !a.isDestroyed();
    }
}
