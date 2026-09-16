# CoolerBT
Android application for controlling a water cooler through Bluetooth Classic SPP.

## Protocol
- `1`: pump toggle
- `2`: slow speed
- `3`: fast speed
- `4`: manual/automatic mode
- `T`: timer enable/disable
- `s`: request status
- `ONhhmm`: timer ON
- `OFFhhmm`: timer OFF
- `Shhmm`: RTC time

Expected status example: `T:25.5,H:60.0,M:1,P:0,F:2,TM:1`


## ساخت APK با گوشی و GitHub Actions

1. این پروژه را در یک Repository در GitHub آپلود کنید.
2. وارد تب **Actions** شوید.
3. Workflow با نام **Build Android APK** را باز کنید.
4. گزینه **Run workflow** را بزنید.
5. بعد از پایان Build، وارد همان اجرای Workflow شوید.
6. از بخش **Artifacts** فایل `CoolerBT-debug-apk` را دریافت کنید.
7. فایل APK داخل آن را روی گوشی نصب کنید.

این Workflow با **JDK 17** اجرا می‌شود و برای Android Gradle Plugin جدید تنظیم شده است.
