#!/usr/bin/env python3
"""Exercise the platform translation lookup and the Genius placement with synthetic payloads."""
import subprocess
from regression_runtime import ROOT, SHARED, REPORTS, android_jar, classpath, java_tool, json_jar

work = REPORTS / "platform-translation"
work.mkdir(parents=True, exist_ok=True)

sources = [
    SHARED / "LyricsTextComparison.java",
    SHARED / "PlatformLyricRow.java",
    SHARED / "PlatformLyricSource.java",
    SHARED / "ChineseLyricText.java",
    SHARED / "PlatformRecordingMatcher.java",
    SHARED / "PlatformTranslationAligner.java",
    SHARED / "PlatformHttpTransport.java",
    SHARED / "NeteaseLyricSource.java",
    SHARED / "QqLyricSource.java",
    SHARED / "PlatformTranslationSettings.java",
    SHARED / "PlatformTranslationService.java",
    SHARED / "SongAnnotation.java",
    SHARED / "SongMeaning.java",
    SHARED / "GeniusClient.java",
    SHARED / "AnnotationLineMapper.java",
    SHARED / "SongMeaningService.java",
    ROOT / "tests/kr/ivlis/ivlyricsandroid/PlatformTranslationRegression.java",
]

deps = classpath(work, json_jar(), android_jar())
subprocess.run([java_tool("javac"), "-encoding", "UTF-8", "-cp", deps, "-d", str(work)]
               + [str(source) for source in sources], check=True)
subprocess.run([java_tool("java"), "-Dfile.encoding=UTF-8", "-cp", deps,
                "kr.ivlis.ivlyricsandroid.PlatformTranslationRegression"], check=True)
