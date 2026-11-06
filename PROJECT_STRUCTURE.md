# بنية المشروع

- `app/src/main/java/com/example/bt115helper/MainActivity.java`: الواجهة العربية، تحذير مسح بيانات Bluetooth، وطلب أذونات Bluetooth.
- `BluetoothWorkerService.java`: محاولة تنفيذ الأوامر بالترتيب عبر `su -c`، ثم البحث عن أسماء BT115 وعرض حالة الاتصال.
- `BootReceiver.java`: يبدأ خدمة التطبيق عند الإقلاع إذا فعّل المستخدم الخيار.
- `app/src/main/res`: أيقونة Bluetooth وموارد واجهة Android TV.
- `.github/workflows/android.yml`: بناء APK debug ورفعه كـ Artifact.

لا توجد مكتبات أو خدمات تطبيق وسيط. يتطلب تشغيل أوامر النظام وجود `su` وصلاحية root ضمن firmware الجهاز.
