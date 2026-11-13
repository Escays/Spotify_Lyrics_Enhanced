#!/usr/bin/env python3
"""Manual diagnostic: what one query actually returns from each platform."""
import subprocess
import sys
from regression_runtime import ROOT, SHARED, REPORTS, android_jar, classpath, java_tool, json_jar

work = REPORTS / "platform-diagnostic"
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
    SHARED / "KugouLyricSource.java",
    SHARED / "SongAnnotation.java",
    SHARED / "SongMeaning.java",
    SHARED / "GeniusClient.java",
    SHARED / "AnnotationLineMapper.java",
    SHARED / "SongMeaningService.java",
    SHARED / "PlatformTranslationSettings.java",
    SHARED / "PlatformTranslationService.java",
    ROOT / "tests/kr/ivlis/ivlyricsandroid/PlatformQueryDiagnostic.java",
]

deps = classpath(work, json_jar(), android_jar())
subprocess.run([java_tool("javac"), "-encoding", "UTF-8", "-cp", deps, "-d", str(work)]
               + [str(source) for source in sources], check=True)
subprocess.run([java_tool("java"), "-Dfile.encoding=UTF-8", "-cp", deps,
                "kr.ivlis.ivlyricsandroid.PlatformQueryDiagnostic"] + sys.argv[1:], check=True)
