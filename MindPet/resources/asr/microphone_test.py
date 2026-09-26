import argparse
import json
import sys
import time

import numpy as np
import sounddevice as sd


def emit(payload):
    sys.stdout.write(json.dumps(payload, ensure_ascii=False) + "\n")
    sys.stdout.flush()


parser = argparse.ArgumentParser()
parser.add_argument("--device", type=int, default=-1)
parser.add_argument("--sample-rate", type=int, default=16000)
args = parser.parse_args()
last_emit = 0.0
silent_since = time.monotonic()
silence_reported = False


def callback(indata, frames, time_info, status):
    global last_emit, silent_since, silence_reported
    if status:
        emit({"type": "warning", "message": str(status)})
    now = time.monotonic()
    if now - last_emit < 0.08:
        return
    data = np.asarray(indata, dtype=np.float32)
    rms_values = np.sqrt(np.mean(np.square(data), axis=0)).reshape(-1)
    levels = [min(1.0, float(value) * 12.0) for value in rms_values]
    peak = max(levels) if levels else 0.0
    emit({"type": "test_level", "levels": levels, "peak": peak})
    last_emit = now
    if peak < 0.001:
        if now - silent_since >= 3 and not silence_reported:
            emit({"type": "test_silent", "message": "没有检测到麦克风声音"})
            silence_reported = True
    else:
        silent_since = now
        silence_reported = False


def open_compatible_stream(device_id, device_info):
    try:
        default_rate = int(round(float(device_info.get("default_samplerate") or 0)))
    except (TypeError, ValueError):
        default_rate = 0
    rates = list(dict.fromkeys(
        rate for rate in (default_rate, args.sample_rate, 48000, 44100, 32000, 16000)
        if rate > 0
    ))
    try:
        max_channels = int(device_info.get("max_input_channels") or 1)
    except (TypeError, ValueError):
        max_channels = 1
    channels_to_try = list(dict.fromkeys((max(1, min(2, max_channels)), 1)))
    last_error = None

    for channels in channels_to_try:
        for sample_rate in rates:
            candidate = None
            try:
                candidate = sd.InputStream(
                    device=device_id,
                    samplerate=sample_rate,
                    channels=channels,
                    dtype="float32",
                    callback=callback,
                    blocksize=max(800, int(sample_rate * 0.1)),
                )
                candidate.start()
                return candidate, channels, sample_rate
            except Exception as error:
                last_error = error
                if candidate is not None:
                    try:
                        if candidate.active:
                            candidate.stop()
                        candidate.close()
                    except Exception:
                        pass

    raise RuntimeError(f"无法打开所选麦克风（尝试采样率：{', '.join(map(str, rates))} Hz）：{last_error}")


try:
    device_id = None if args.device < 0 else args.device
    device = sd.query_devices(kind="input") if device_id is None else sd.query_devices(device_id)
    stream, channels, sample_rate = open_compatible_stream(device_id, device)
    emit({
        "type": "test_ready",
        "device": str(device["name"]),
        "channels": channels,
        "sampleRate": sample_rate,
    })
except Exception as error:
    emit({"type": "test_error", "message": str(error)})
    sys.exit(2)

for line in sys.stdin:
    try:
        command = json.loads(line)
    except Exception:
        continue
    if command.get("action") == "stop":
        break

stream.stop()
stream.close()
emit({"type": "test_stopped"})
