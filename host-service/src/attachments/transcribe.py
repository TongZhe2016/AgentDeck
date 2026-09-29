"""Local transcription. Model weights are installed separately, never fetched here."""
import json
import sys

from faster_whisper import WhisperModel

model = WhisperModel(sys.argv[1], device="cpu", compute_type="int8", local_files_only=True)
segments, info = model.transcribe(sys.argv[2], beam_size=5, vad_filter=True)
print(json.dumps({"text": "".join(segment.text for segment in segments).strip()}, ensure_ascii=False))
