# بنية المشروع

- `app/src/main/java/com/example/bt115helper/MainActivity.java`: الواجهة العربية، التأكيد قبل تنفيذ أوامر المسح، طلب إذن Bluetooth.
- `BluetoothWorkerService.java`: الخدمة الأمامية، محاولة تنفيذ أوامر النظام، البحث عن الأسماء المحددة وطلب الاقتران عبر Android.
- `BootReceiver.java`: تشغيل الخدمة بعد الإقلاع وفق الاختيار المحفوظ.
- `app/src/main/res`: الأيقونات والسمة المرئية.
- `Android.bp`: وصف تكامل AOSP كتطبيق نظام مميز مع توقيع platform.
- `system/privapp-permissions-com.example.bt115helper.xml`: قائمة صلاحيات النظام التي ينبغي السماح بها ضمن صورة النظام.
- `.github/workflows/android.yml`: بناء APK debug ورفعه كـ artifact.
