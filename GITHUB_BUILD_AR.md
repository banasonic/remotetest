## ارفع المشروع وابنِ APK

- ارفع المجلد كاملًا إلى مستودع GitHub على الفرع `main`.
- شغّل **Actions → Build Android TV APK** أو ادفع commit جديدًا.
- نزّل artifact باسم `bt115-tv-helper-debug-apk` من تشغيل الـ workflow.

هذا APK debug عادي. لتشغيل أوامر النظام على جهاز غير مروّت، لا يكفي APK؛ يلزم دمجه كتطبيق `priv-app` في AOSP وتوقيعه بمفتاح platform المطابق للـ firmware وإضافة قائمة السماح المرفقة. لا يمكن استبدال توقيع المصنع أو إنشاؤه من GitHub Actions.
