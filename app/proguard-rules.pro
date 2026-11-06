# FFmpegKit calls back into Java from native code by name. The AAR ships these rules as
# proguard.txt; they are repeated here because the AAR is a local file dependency.
-keep class com.arthenica.ffmpegkit.FFmpegKitConfig {
    native <methods>;
    void log(long, int, byte[]);
    void statistics(long, int, float, float, long , double, double, double);
    int safOpen(int);
    int safClose(int);
}
-keep class com.arthenica.ffmpegkit.AbiDetect {
    native <methods>;
}
