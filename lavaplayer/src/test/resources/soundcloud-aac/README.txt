Self-generated test fixture (a 4 s 440 Hz sine tone, AAC-LC stereo 44.1 kHz, fMP4 HLS):
ffmpeg -f lavfi -i "sine=frequency=440:duration=4:sample_rate=44100" -ac 2 -c:a aac -b:a 48k \
  -f hls -hls_time 2 -hls_segment_type fmp4 -hls_fmp4_init_filename init.mp4 \
  -hls_segment_filename 'seg%d.m4s' -hls_playlist_type vod list.m3u8
The playlist uses relative URLs on purpose (SoundCloudAacPlaybackTest resolves them).
(22.05 kHz was tried first and plays about 8% short in LavaPlayer's AAC path; SoundCloud serves 44.1 kHz.)
