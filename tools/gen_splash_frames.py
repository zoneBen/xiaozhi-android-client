#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""生成冷启动头屏的动画帧（气泡呼吸 + 声波扩散）。

用法：
    python3 tools/gen_splash_frames.py

输出：app/src/main/res/drawable-xxhdpi/splash_00.png ... splash_07.png
（8 帧正方形、背景透明；底色由 layer-list 里的纯色 item 填，
 这样在任何屏幕比例下圆都不会被 FILL 拉成椭圆。）

依赖 Pillow。

改帧数 / 节奏时要同步改两处 Kotlin/XML：
  - app/src/main/res/drawable/splash_frames.xml 里的 item 条数与 duration
  - SplashActivity 里的 FRAME_RES 数组与 FRAME_DURATION_MS

设计上踩过的坑，改之前先看一眼：
  - 帧必须是按「相位」算出来的（phase = i/N），首尾才能无缝循环，不能手画。
  - 声波圈的半径只取 0.21~0.36W，贴着气泡外沿扩散。铺到画布边缘的话，
    圈会跑到视野角落，反而看不出「以气泡为中心」。
  - 试过用「实心脉冲」代替细环来加强对比度：30% 不透明度叠模糊会糊成
    一大块灰盘，很脏，弃掉。
  - 气泡底下的柔光晕收到 20/255 就够。做成 38~54 且会呼吸的话，
    在 #F1F5FA 浅底上糊成一片灰蓝。
"""
import math
import os
import sys

from PIL import Image, ImageDraw, ImageFilter

FRAME_COUNT = 8
SUPERSAMPLE = 3
CANVAS_PX = 660  # drawable-xxhdpi 下 660px = 220dp，与 activity_splash.xml 一致

# logo 主色量化后是 #61D1D7 / #52AACD / #8AE0DB，声波取其中最深的 #52AACD：
# 在 #F1F5FA 底色上才有对比度。直接用不透明像素均值（#62C1D3）会偏浅、糊进背景。
RING_COLOR = (82, 170, 205)

MASCOT_RATIO = 0.42  # 气泡长边占画布比例
GLOW_RATIO = 0.20
GLOW_ALPHA = 20
BREATH_AMPLITUDE = 0.035
RING_START = 0.21
RING_TRAVEL = 0.14
RING_ALPHA = 0.55
RING_WIDTH = 0.014


def main() -> int:
    repo_root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    src = os.path.join(repo_root, "logo.png")
    out_dir = os.path.join(repo_root, "app", "src", "main", "res", "drawable-xxhdpi")

    if not os.path.exists(src):
        print("找不到 %s —— 仓库根目录的 logo.png 是这些帧的图形来源" % src)
        return 1
    os.makedirs(out_dir, exist_ok=True)

    size = CANVAS_PX * SUPERSAMPLE

    logo = Image.open(src).convert("RGBA")
    mascot = logo.crop(logo.getbbox())
    mascot_w = int(MASCOT_RATIO * size)
    mascot_h = max(1, round(mascot_w * mascot.size[1] / mascot.size[0]))
    mascot_scaled = mascot.resize((mascot_w, mascot_h), Image.LANCZOS)

    # 气泡底下的一层静止柔光，给点纵深。只覆盖气泡附近，
    # 避免整幅渐变把 PNG 撑大。
    glow_radius = GLOW_RATIO * size
    glow = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    ImageDraw.Draw(glow).ellipse(
        [
            size / 2 - glow_radius,
            size / 2 - glow_radius,
            size / 2 + glow_radius,
            size / 2 + glow_radius,
        ],
        fill=RING_COLOR + (GLOW_ALPHA,),
    )
    glow = glow.filter(ImageFilter.GaussianBlur(0.022 * size))

    for index in range(FRAME_COUNT):
        phase = index / FRAME_COUNT
        frame = Image.new("RGBA", (size, size), (0, 0, 0, 0))
        frame.alpha_composite(glow)

        # 三道同心环，相位各错 1/3 轮，所以任何时候都有 2~3 圈在途。
        draw = ImageDraw.Draw(frame)
        for ring in range(3):
            ring_phase = (phase + ring / 3.0) % 1.0
            radius = (RING_START + RING_TRAVEL * ring_phase) * size
            alpha = int(255 * RING_ALPHA * (1.0 - ring_phase) ** 1.1)
            if alpha <= 0:
                continue
            width = max(1, int(RING_WIDTH * size * (1.0 - 0.35 * ring_phase)))
            draw.ellipse(
                [size / 2 - radius, size / 2 - radius, size / 2 + radius, size / 2 + radius],
                outline=RING_COLOR + (alpha,),
                width=width,
            )

        # 气泡随相位轻微呼吸，幅度小、节奏慢，不喧宾夺主。
        scale = 1.0 + BREATH_AMPLITUDE * math.sin(2 * math.pi * phase)
        draw_w, draw_h = int(mascot_w * scale), int(mascot_h * scale)
        frame.alpha_composite(
            mascot_scaled.resize((draw_w, draw_h), Image.LANCZOS),
            ((size - draw_w) // 2, (size - draw_h) // 2),
        )

        path = os.path.join(out_dir, "splash_%02d.png" % index)
        frame.resize((CANVAS_PX, CANVAS_PX), Image.LANCZOS).save(path, optimize=True)
        print("%s  %d bytes" % (path, os.path.getsize(path)))

    print("完成：%d 帧，画布 %dpx（%ddp）" % (FRAME_COUNT, CANVAS_PX, CANVAS_PX // 3))
    return 0


if __name__ == "__main__":
    sys.exit(main())
