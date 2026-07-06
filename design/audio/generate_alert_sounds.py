"""Gera sons originais de notificação para o Alertou.

O primeiro som fica como assinatura principal. Os demais usam timbres e ritmos
mais variados para não parecerem apenas versões do mesmo sino.
"""

import math
import random
import struct
import wave
from pathlib import Path


SAMPLE_RATE = 44_100
OUTPUT_DIR = Path(__file__).with_name("suggestions")


def note_frequency(note):
    names = {"C": 0, "C#": 1, "D": 2, "D#": 3, "E": 4, "F": 5,
             "F#": 6, "G": 7, "G#": 8, "A": 9, "A#": 10, "B": 11}
    name = note[:-1]
    octave = int(note[-1])
    midi = 12 * (octave + 1) + names[name]
    return 440.0 * (2.0 ** ((midi - 69) / 12.0))


def clamp(value, low=-1.0, high=1.0):
    return max(low, min(high, value))


def envelope(time, duration, attack=0.018, release=0.22):
    rise = min(1.0, time / attack)
    remaining = max(0.0, duration - time)
    fall = min(1.0, remaining / release)
    return math.sin(rise * math.pi / 2.0) * math.sin(fall * math.pi / 2.0)


def bell_sample(frequency, local_time, duration, softness):
    env = envelope(local_time, duration)
    decay = math.exp(-local_time * (2.4 + softness))
    fundamental = math.sin(2.0 * math.pi * frequency * local_time)
    warm = math.sin(2.0 * math.pi * frequency * 2.003 * local_time + 0.18) * 0.26
    shimmer = math.sin(2.0 * math.pi * frequency * 3.997 * local_time + 0.42) * 0.09
    return (fundamental + warm + shimmer) * env * decay


def voice_sample(kind, frequency, local_time, duration, softness, rng):
    if kind == "bell":
        return bell_sample(frequency, local_time, duration, softness)

    if kind == "round":
        env = envelope(local_time, duration, attack=0.030, release=0.28)
        decay = math.exp(-local_time * (1.65 + softness * 0.35))
        tone = math.sin(2.0 * math.pi * frequency * local_time)
        tone += math.sin(2.0 * math.pi * frequency * 1.5 * local_time) * 0.18
        tone += math.sin(2.0 * math.pi * frequency * 0.5 * local_time) * 0.12
        return tone * env * decay

    if kind == "pluck":
        env = envelope(local_time, duration, attack=0.006, release=0.13)
        decay = math.exp(-local_time * (6.2 + softness))
        tone = math.sin(2.0 * math.pi * frequency * local_time)
        tone += math.sin(2.0 * math.pi * frequency * 2.01 * local_time) * 0.22
        tone += math.sin(2.0 * math.pi * frequency * 3.02 * local_time) * 0.08
        return tone * env * decay

    if kind == "wood":
        env = envelope(local_time, duration, attack=0.003, release=0.08)
        decay = math.exp(-local_time * (11.0 + softness))
        tone = math.tanh(math.sin(2.0 * math.pi * frequency * local_time) * 2.5)
        tone += math.sin(2.0 * math.pi * frequency * 2.7 * local_time) * 0.18
        click = rng.uniform(-1.0, 1.0) * math.exp(-local_time * 60.0) * 0.10
        return (tone + click) * env * decay

    if kind == "pulse":
        env = envelope(local_time, duration, attack=0.010, release=0.16)
        decay = math.exp(-local_time * (3.9 + softness))
        soft_square = math.tanh(math.sin(2.0 * math.pi * frequency * local_time) * 1.9)
        tone = soft_square * 0.62 + math.sin(2.0 * math.pi * frequency * 0.5 * local_time) * 0.22
        return tone * env * decay

    if kind == "chirp":
        env = envelope(local_time, duration, attack=0.004, release=0.10)
        slide = 1.0 + 0.18 * math.exp(-local_time * 18.0)
        phase = 2.0 * math.pi * frequency * slide * local_time
        tone = math.sin(phase) + math.sin(phase * 2.01) * 0.16
        return tone * env * math.exp(-local_time * 7.0)

    if kind == "drop":
        env = envelope(local_time, duration, attack=0.006, release=0.20)
        slide = 1.32 - 0.36 * min(1.0, local_time / max(duration, 0.001))
        phase = 2.0 * math.pi * frequency * slide * local_time
        tone = math.sin(phase) + math.sin(phase * 0.5) * 0.20
        return tone * env * math.exp(-local_time * 3.6)

    if kind == "bass":
        env = envelope(local_time, duration, attack=0.014, release=0.18)
        tone = math.sin(2.0 * math.pi * frequency * local_time)
        tone += math.sin(2.0 * math.pi * frequency * 2.0 * local_time) * 0.12
        return tone * env * math.exp(-local_time * 3.0)

    return bell_sample(frequency, local_time, duration, softness)


def add_noise(left, right, rng, start, duration, amount, pan=0.0, color="sparkle"):
    first = int(start * SAMPLE_RATE)
    last = min(len(left), int((start + duration) * SAMPLE_RATE))
    previous = 0.0
    for frame in range(first, last):
        time = (frame - first) / SAMPLE_RATE
        noise = rng.uniform(-1.0, 1.0)
        if color == "paper":
            value = (noise * 0.55 + previous * 0.45) * math.exp(-time * 18.0) * amount
            previous = value
        else:
            value = (noise - previous * 0.92) * math.exp(-time * 22.0) * amount
            previous = noise
        left[frame] += value * math.sqrt((1.0 - pan) / 2.0)
        right[frame] += value * math.sqrt((1.0 + pan) / 2.0)


def render(filename, duration, events, textures=None, echoes=((0.075, 0.12), (0.145, 0.055))):
    frame_count = int(duration * SAMPLE_RATE)
    left = [0.0] * frame_count
    right = [0.0] * frame_count
    rng = random.Random(filename)

    for event in events:
        if len(event) == 6:
            start, note, note_duration, volume, pan, softness = event
            kind = "bell"
        else:
            start, note, note_duration, volume, pan, softness, kind = event
        frequency = note_frequency(note)
        first = int(start * SAMPLE_RATE)
        last = min(frame_count, int((start + note_duration) * SAMPLE_RATE))
        for frame in range(first, last):
            local_time = frame / SAMPLE_RATE - start
            value = voice_sample(kind, frequency, local_time, note_duration, softness, rng) * volume
            left[frame] += value * math.sqrt((1.0 - pan) / 2.0)
            right[frame] += value * math.sqrt((1.0 + pan) / 2.0)

    for texture in textures or []:
        add_noise(left, right, rng, **texture)

    for delay_seconds, gain in echoes:
        delay = int(delay_seconds * SAMPLE_RATE)
        for frame in range(delay, frame_count):
            delayed_left = left[frame - delay]
            delayed_right = right[frame - delay]
            left[frame] += delayed_right * gain
            right[frame] += delayed_left * gain

    peak = max(max(abs(value) for value in left), max(abs(value) for value in right), 0.001)
    scale = 0.68 / peak
    OUTPUT_DIR.mkdir(parents=True, exist_ok=True)
    with wave.open(str(OUTPUT_DIR / filename), "wb") as output:
        output.setnchannels(2)
        output.setsampwidth(2)
        output.setframerate(SAMPLE_RATE)
        frames = bytearray()
        for left_value, right_value in zip(left, right):
            frames.extend(struct.pack(
                "<hh",
                int(clamp(left_value * scale) * 32767),
                int(clamp(right_value * scale) * 32767),
            ))
        output.writeframes(frames)


def main():
    # 01 fica perto da primeira assinatura que você preferiu.
    render("01_alertou_assinatura.wav", 1.08, [
        (0.00, "E5", 0.54, 0.72, -0.18, 0.5, "bell"),
        (0.15, "G#5", 0.58, 0.62, 0.10, 0.8, "bell"),
        (0.31, "B5", 0.68, 0.66, 0.24, 1.0, "bell"),
    ], textures=[{"start": 0.09, "duration": 0.19, "amount": 0.015, "pan": 0.25}])

    render("02_oferta_encontrada.wav", 1.05, [
        (0.00, "A4", 0.32, 0.62, -0.22, 0.8, "wood"),
        (0.13, "E5", 0.36, 0.54, 0.12, 1.1, "wood"),
        (0.34, "A5", 0.50, 0.44, 0.24, 1.0, "round"),
    ], echoes=((0.060, 0.10),))

    render("03_brisa_verde.wav", 1.42, [
        (0.00, "B4", 0.92, 0.34, -0.24, 1.8, "round"),
        (0.26, "E5", 0.86, 0.38, 0.05, 1.9, "round"),
        (0.54, "G#5", 0.64, 0.26, 0.28, 2.0, "bell"),
    ], textures=[{"start": 0.04, "duration": 0.58, "amount": 0.004, "pan": -0.10, "color": "paper"}],
        echoes=((0.120, 0.10), (0.240, 0.05)))

    render("04_pingo_de_preco.wav", 0.76, [
        (0.00, "E6", 0.24, 0.70, -0.16, 0.5, "chirp"),
        (0.22, "B5", 0.34, 0.50, 0.18, 0.8, "pluck"),
    ], echoes=((0.050, 0.08),))

    render("05_valeu_a_pena.wav", 1.22, [
        (0.00, "G4", 0.42, 0.50, -0.25, 1.0, "pluck"),
        (0.15, "B4", 0.44, 0.50, -0.05, 1.0, "round"),
        (0.31, "D5", 0.48, 0.52, 0.12, 1.1, "round"),
        (0.49, "G5", 0.56, 0.44, 0.27, 1.4, "bell"),
    ], echoes=((0.090, 0.10),))

    render("06_cupom_suave.wav", 1.00, [
        (0.04, "C#5", 0.28, 0.48, -0.20, 0.8, "pluck"),
        (0.19, "F#5", 0.30, 0.44, 0.06, 0.9, "pluck"),
        (0.39, "A5", 0.42, 0.34, 0.22, 1.1, "round"),
    ], textures=[{"start": 0.00, "duration": 0.16, "amount": 0.018, "pan": -0.20, "color": "paper"}],
        echoes=((0.070, 0.07),))

    render("07_preco_caiu.wav", 0.96, [
        (0.00, "B5", 0.50, 0.56, -0.18, 1.0, "drop"),
        (0.22, "E5", 0.42, 0.50, 0.16, 1.0, "bass"),
    ], echoes=((0.085, 0.08),))

    render("08_toque_verde.wav", 1.10, [
        (0.00, "F#4", 0.44, 0.44, -0.24, 1.2, "pulse"),
        (0.22, "C#5", 0.46, 0.52, 0.00, 1.1, "pulse"),
        (0.44, "F#5", 0.42, 0.36, 0.22, 1.5, "round"),
    ], echoes=((0.100, 0.08),))

    render("09_oferta_flash.wav", 0.68, [
        (0.00, "D6", 0.16, 0.58, -0.22, 0.4, "chirp"),
        (0.10, "F#6", 0.16, 0.58, 0.02, 0.4, "chirp"),
        (0.20, "A6", 0.22, 0.48, 0.24, 0.6, "chirp"),
    ], textures=[{"start": 0.03, "duration": 0.12, "amount": 0.010, "pan": 0.25}],
        echoes=((0.040, 0.06),))

    render("10_achado_bom.wav", 1.14, [
        (0.00, "A4", 0.22, 0.52, -0.25, 0.8, "wood"),
        (0.11, "A5", 0.22, 0.42, 0.16, 0.8, "wood"),
        (0.34, "E5", 0.50, 0.38, 0.05, 1.4, "bell"),
    ], textures=[{"start": 0.00, "duration": 0.07, "amount": 0.012, "pan": -0.10}],
        echoes=((0.075, 0.11), (0.155, 0.045)))

    render("11_sinal_de_preco.wav", 1.02, [
        (0.00, "G5", 0.28, 0.48, -0.22, 0.8, "pulse"),
        (0.18, "G5", 0.28, 0.42, 0.20, 0.8, "pulse"),
        (0.42, "D6", 0.32, 0.40, 0.00, 1.0, "chirp"),
    ], echoes=((0.060, 0.08),))

    render("12_ping_promocao.wav", 0.82, [
        (0.00, "E6", 0.36, 0.68, -0.08, 0.45, "bell"),
        (0.31, "E6", 0.24, 0.24, 0.20, 1.1, "round"),
    ], echoes=((0.110, 0.09),))

    render("13_desconto_leve.wav", 1.26, [
        (0.00, "D4", 0.32, 0.36, -0.22, 1.0, "bass"),
        (0.18, "F#5", 0.44, 0.42, 0.00, 1.4, "round"),
        (0.48, "A5", 0.54, 0.30, 0.25, 1.8, "bell"),
    ], textures=[{"start": 0.02, "duration": 0.10, "amount": 0.010, "pan": -0.20, "color": "paper"}],
        echoes=((0.130, 0.08),))

    render("14_alerta_macio.wav", 1.18, [
        (0.00, "C5", 0.70, 0.38, -0.24, 2.0, "round"),
        (0.28, "G5", 0.68, 0.34, 0.08, 2.0, "round"),
        (0.52, "C6", 0.46, 0.24, 0.26, 2.1, "round"),
    ], echoes=((0.140, 0.07), (0.280, 0.035)))

    render("15_boa_compra.wav", 1.16, [
        (0.00, "E5", 0.18, 0.44, -0.24, 0.7, "wood"),
        (0.10, "G#5", 0.20, 0.48, -0.05, 0.7, "pluck"),
        (0.21, "B5", 0.20, 0.46, 0.14, 0.8, "pluck"),
        (0.34, "E6", 0.30, 0.44, 0.25, 0.9, "bell"),
        (0.58, "B5", 0.34, 0.30, 0.05, 1.5, "round"),
    ], textures=[{"start": 0.00, "duration": 0.08, "amount": 0.010, "pan": -0.15}],
        echoes=((0.055, 0.08), (0.125, 0.04)))


if __name__ == "__main__":
    main()
