package com.glasslauncher.tv.data

/**
 * App language (System → Language). English is the source; the others cover the menu, the settings sections,
 * the first-run setup and the most used rows. Anything not translated yet stays in English.
 */
object I18n {
    @Volatile var lang: String = "en"

    /** Code → its own name, in the order the setting cycles through. */
    val LANGS = linkedMapOf(
        "en" to "English", "ar" to "العربية", "fa" to "فارسی", "tr" to "Türkçe", "fr" to "Français",
        "es" to "Español", "de" to "Deutsch", "ru" to "Русский", "pt" to "Português",
    )
    private val COLS = listOf("ar", "fa", "tr", "fr", "es", "de", "ru", "pt")

    fun tr(s: String): String {
        if (lang == "en" || s.isBlank()) return s
        val col = COLS.indexOf(lang).takeIf { it >= 0 } ?: return s
        TABLE[s]?.getOrNull(col)?.takeIf { it.isNotBlank() }?.let { return it }
        if (s.startsWith("  · ")) TABLE[s.removePrefix("  · ")]?.getOrNull(col)?.takeIf { it.isNotBlank() }?.let { return "  · $it" }
        return s
    }

    // English | Arabic | Persian | Turkish | French | Spanish | German | Russian | Portuguese
    private val TABLE: Map<String, List<String>> = """
Settings|الإعدادات|تنظیمات|Ayarlar|Réglages|Ajustes|Einstellungen|Настройки|Ajustes
Connections|الاتصالات|اتصال‌ها|Bağlantılar|Connexions|Conexiones|Verbindungen|Подключения|Conexões
Catalogs & add-ons|الكتلوكات والإضافات
Add-ons|الإضافات
Carousel catalogs|كتلوكات الكاروسيل
Catalogs in the carousel|الكتلوكات بالكاروسيل
In the carousel|بالكاروسيل
Your add-ons|إضافاتك
Add an add-on|ضيف إضافة
Add-on|الإضافة
No add-ons yet|ماكو إضافات بعد
Add from my phone (QR)|ضيف من الموبايل (QR)
Add add-on link|ضيف رابط إضافة
Connect to Nuvio|اربط ويا Nuvio
Remove add-on|احذف الإضافة
From your Nuvio account|من حساب Nuvio مالتك
Add-ons from my Nuvio account|إضافات حساب Nuvio مالتي
Catalogs from Stremio's add-ons|كتلوكات إضافات Stremio
Refresh catalogs|حدث الكتلوكات
Select all catalogs|اختار كل الكتلوكات
Select none|لا تختار شي
Catalogs for apps|كتلوكات للتطبيقات
Apps and their catalogs|التطبيقات وكتلوكاتها
Continue Watching in app carousels|متابعة المشاهدة بكاروسيل التطبيقات
Play opens the app|Play يفتح التطبيق
Play opens the streaming app|Play يفتح تطبيق البث
Find links with JustWatch|دور الروابط بـJustWatch
Country|البلد
Accounts & profiles|الحسابات والبروفايلات|حساب‌ها و پروفایل‌ها|Hesaplar ve profiller|Comptes et profils|Cuentas y perfiles|Konten & Profile|Аккаунты и профили|Contas e perfis
Continue Watching & Library|المتابعة والمكتبة|ادامه تماشا و کتابخانه|İzlemeye devam ve Kitaplık|Reprendre et Bibliothèque|Seguir viendo y Biblioteca|Weiterschauen & Mediathek|Продолжить и Библиотека|Continuar e Biblioteca
Look & menu|المظهر والمنيو|ظاهر و منو|Görünüm ve menü|Apparence et menu|Aspecto y menú|Aussehen & Menü|Вид и меню|Aparência e menu
Background & screensaver|الخلفية والسكرين سيفر|پس‌زمینه و محافظ صفحه|Arka plan ve ekran koruyucu|Fond et économiseur|Fondo y salvapantallas|Hintergrund & Bildschirmschoner|Фон и заставка|Fundo e proteção de tela
Music & app rows|الموسيقى وصفوف التطبيقات|موسیقی و ردیف برنامه‌ها|Müzik ve uygulama satırları|Musique et rangées d'apps|Música y filas de apps|Musik & App-Reihen|Музыка и ряды приложений|Música e filas de apps
Smart home|البيت الذكي|خانه هوشمند|Akıllı ev|Maison connectée|Hogar inteligente|Smart Home|Умный дом|Casa inteligente
Other accounts|حسابات ثانية
Profiles|البروفايلات
Profile|البروفايل
At start|عند التشغيل
Name|الاسم
Picture|الصورة
Keep accounts the same|خلي الحسابات متطابقة
Where it comes from|منين تجي
Sources|المصادر
Clear the list|امسح القائمة
Library look|شكل المكتبة
Lists in the Library|القوائم بالمكتبة
+ saves to|زر + يحفظ بـ
Library rows|صفوف المكتبة
Platform rows|صفوف المنصات
Services|الخدمات
Slides|السلايدات
What it shows|شنو يعرض
Title & info|العنوان والمعلومات
Buttons|الأزرار
Dots|النقاط
Pictures|الصور
Trailers|التريلرات
Place & size|المكان والحجم
Dock glass|زجاج الدوك
Glass style|ستايل الزجاج
Arrange apps|رتب التطبيقات
Icons|الأيقونات
Install an icon pack|نصب باقة أيقونات
Extra icons|أيقونات إضافية
Inputs|المداخل
Glass|الزجاج
Shapes & text|الأشكال والخط
Selection|التحديد
Behind pages|ورا الصفحات
Top bar|الشريط الفوقاني
Clock & weather|الساعة والطقس
Menu|المنيو
Menu items|عناصر المنيو
TV info|معلومات التلفزيون
Photos|الصور (4K)
Your pictures & videos|صورك وفيديواتك
My pictures & videos|صوري وفيديواتي
Screensaver videos as background|فيديوات السكرين سيفر كخلفية
Over the background|فوق الخلفية
Large clock|الساعة الكبيرة
Info over photos|المعلومات فوق الصور
Screensaver · When|السكرين سيفر · شوكت
Screensaver · What plays|السكرين سيفر · شنو يشتغل
Screensaver · Trailers|السكرين سيفر · التريلرات
Screensaver · Videos|السكرين سيفر · الفيديوات
Screensaver · Locked videos|السكرين سيفر · الفيديوات المقفولة
Screensaver · On screen|السكرين سيفر · على الشاشة
Screensaver · Also on it|السكرين سيفر · وياه هم
Collections|المجموعات
My videos|فيديواتي
Music|الموسيقى
Music apps|تطبيقات الموسيقى
Music island|آيلند الموسيقى
Island|الآيلند
App rows (Channels)|صفوف التطبيقات (Channels)
Apps with channels|التطبيقات اللي بيها صفوف
Page background|خلفية الصفحة
Hue lights|أضواء Hue
Bridge|البريدج
Lights & areas|الأضواء والمناطق
Hue colours|ألوان Hue
Hue follows the selection|Hue يتبع التحديد
Hue · Afterwards|Hue · بعدين
Corner camera|كاميرا الزاوية
Multi-view|العرض المتعدد
Doorbell|جرس الباب
Pop-up look|شكل النافذة
Home Assistant link|ربط Home Assistant
Home button & start|زر Home والتشغيل
Remote buttons|أزرار الريموت
Other TVs & backups|التلفزيونات الثانية والنسخ الاحتياطية
Sync between TVs|المزامنة بين التلفزيونات
App lock|قفل التطبيقات
TV|التلفزيون
TV access (ADB)|صلاحية التلفزيون (ADB)
New|جديد|جدید|Yeni|Nouveau|Nuevo|Neu|Новое|Novo
Changed|المتغيّر|تغییرکرده|Değişen|Modifié|Cambiado|Geändert|Изменено|Alterado
New settings|الإعدادات الجديدة
Changed settings|الإعدادات المتغيّرة
Default|الافتراضي
back to|رجّعها لـ
Back to default|رجّعها للافتراضي
Go to it|روح الها
Cancel|إلغاء
On|مشغّل
Off|مطفي
Mark them all as seen|خليها كلها مشافة
Every setting is as it comes|كل الإعدادات مثل ما تجي
Catalogs|الكتالوجات|کاتالوگ‌ها|Kataloglar|Catalogues|Catálogos|Kataloge|Каталоги|Catálogos
Continue Watching|متابعة المشاهدة|ادامه تماشا|İzlemeye devam et|Reprendre|Seguir viendo|Weiterschauen|Продолжить просмотр|Continuar assistindo
Library|المكتبة|کتابخانه|Kitaplık|Bibliothèque|Biblioteca|Mediathek|Библиотека|Biblioteca
Carousel|الكاروسيل|کاروسل|Karusel|Carrousel|Carrusel|Karussell|Карусель|Carrossel
Dock & icons|الشريط والأيقونات|داک و آیکون‌ها|Dock ve simgeler|Dock et icônes|Dock e iconos|Dock & Symbole|Док и значки|Dock e ícones
Menu & themes|القائمة والثيمات|منو و پوسته‌ها|Menü ve temalar|Menu et thèmes|Menú y temas|Menü & Designs|Меню и темы|Menu e temas
Background & glass|الخلفية والزجاج|پس‌زمینه و شیشه|Arka plan ve cam|Fond et verre|Fondo y cristal|Hintergrund & Glas|Фон и стекло|Fundo e vidro
Screensaver|شاشة التوقف|محافظ صفحه|Ekran koruyucu|Économiseur d'écran|Salvapantallas|Bildschirmschoner|Заставка|Protetor de tela
Cameras|الكاميرات|دوربین‌ها|Kameralar|Caméras|Cámaras|Kameras|Камеры|Câmeras
Performance|الأداء|کارایی|Performans|Performances|Rendimiento|Leistung|Производительность|Desempenho
Manage from phone|الإدارة من الهاتف|مدیریت با گوشی|Telefondan yönet|Gérer depuis le téléphone|Gestionar desde el móvil|Vom Handy verwalten|Управление с телефона|Gerir pelo telemóvel
System|النظام|سیستم|Sistem|Système|Sistema|System|Система|Sistema
About|حول|درباره|Hakkında|À propos|Acerca de|Über|О программе|Sobre
Glassio settings|إعدادات Glassio|تنظیمات Glassio|Glassio ayarları|Réglages Glassio|Ajustes de Glassio|Glassio-Einstellungen|Настройки Glassio|Ajustes do Glassio
Android settings|إعدادات أندرويد|تنظیمات اندروید|Android ayarları|Réglages Android|Ajustes de Android|Android-Einstellungen|Настройки Android|Ajustes do Android
Themes|الثيمات|پوسته‌ها|Temalar|Thèmes|Temas|Designs|Темы|Temas
Camera|الكاميرا|دوربین|Kamera|Caméra|Cámara|Kamera|Камера|Câmera
Background|الخلفية|پس‌زمینه|Arka plan|Arrière-plan|Fondo|Hintergrund|Фон|Fundo
Wi-Fi|واي فاي|وای‌فای|Wi-Fi|Wi-Fi|Wi-Fi|WLAN|Wi-Fi|Wi-Fi
Apps|التطبيقات|برنامه‌ها|Uygulamalar|Apps|Apps|Apps|Приложения|Apps
Hello|مرحباً|سلام|Merhaba|Bonjour|Hola|Hallo|Привет|Olá
Let's connect and customise your Glassio|لنربط حساباتك ونخصص Glassio|بیایید Glassio را وصل و شخصی کنیم|Glassio'nu bağlayıp kişiselleştirelim|Connectons et personnalisons votre Glassio|Conectemos y personalicemos tu Glassio|Lass uns dein Glassio verbinden und anpassen|Давайте подключим и настроим Glassio|Vamos ligar e personalizar o seu Glassio
Get started|ابدأ|شروع|Başla|Commencer|Empezar|Los geht's|Начать|Começar
Restore a backup|استعادة نسخة احتياطية|بازیابی پشتیبان|Yedeği geri yükle|Restaurer une sauvegarde|Restaurar copia|Backup wiederherstellen|Восстановить копию|Restaurar cópia
Later|لاحقاً|بعداً|Sonra|Plus tard|Más tarde|Später|Позже|Mais tarde
Choose a look|اختر الشكل|ظاهر را انتخاب کنید|Bir görünüm seç|Choisissez un style|Elige un estilo|Wähle einen Look|Выберите вид|Escolha um visual
Connect|الربط|اتصال|Bağlan|Connecter|Conectar|Verbinden|Подключить|Ligar
Next|التالي|بعدی|İleri|Suivant|Siguiente|Weiter|Далее|Seguinte
You're all set|كل شي جاهز|همه چیز آماده است|Her şey hazır|Tout est prêt|Todo listo|Alles bereit|Всё готово|Tudo pronto
Start|ابدأ|شروع|Başla|Démarrer|Empezar|Start|Старт|Iniciar
Back|رجوع|بازگشت|Geri|Retour|Atrás|Zurück|Назад|Voltar
Done|تم|تمام|Bitti|Terminé|Listo|Fertig|Готово|Concluído
Saved|المحفوظة|ذخیره‌شده|Kaydedilenler|Enregistrés|Guardados|Gemerkt|Сохранённое|Guardados
Open apps|التطبيقات المفتوحة|برنامه‌های باز|Açık uygulamalar|Apps ouvertes|Apps abiertas|Offene Apps|Открытые приложения|Apps abertas
No apps are open|ماكو تطبيقات مفتوحة|هیچ برنامه‌ای باز نیست|Açık uygulama yok|Aucune app ouverte|No hay apps abiertas|Keine Apps offen|Нет открытых приложений|Nenhuma app aberta
Language|اللغة|زبان|Dil|Langue|Idioma|Sprache|Язык|Idioma
Theme|الثيم|پوسته|Tema|Thème|Tema|Design|Тема|Tema
Picture quality|جودة الصورة|کیفیت تصویر|Resim kalitesi|Qualité d'image|Calidad de imagen|Bildqualität|Качество изображения|Qualidade de imagem
Shuffle all catalogs|خلط كل الكتالوجات|ترکیب همه کاتالوگ‌ها|Tüm katalogları karıştır|Mélanger tous les catalogues|Mezclar todos los catálogos|Alle Kataloge mischen|Перемешать все каталоги|Misturar todos os catálogos
Surprise me|فاجئني|غافلگیرم کن|Beni şaşırt|Surprends-moi|Sorpréndeme|Überrasch mich|Удиви меня|Surpreende-me
White logo|شعار أبيض|لوگوی سفید|Beyaz logo|Logo blanc|Logo blanco|Weißes Logo|Белый логотип|Logótipo branco
Back up now|نسخ احتياطي الآن|پشتیبان‌گیری اکنون|Şimdi yedekle|Sauvegarder|Copiar ahora|Jetzt sichern|Создать копию|Fazer cópia agora
Restore backup|استعادة النسخة|بازیابی پشتیبان|Yedeği geri yükle|Restaurer|Restaurar copia|Wiederherstellen|Восстановить|Restaurar cópia
Hold Home|الضغط المطوّل على Home|نگه داشتن Home|Home'u basılı tut|Maintenir Home|Mantener Home|Home gedrückt halten|Удерживать Home|Manter Home
Glow|التوهج|درخشش|Parıltı|Halo|Brillo|Leuchten|Свечение|Brilho
Icon zoom on focus|تكبير الأيقونة عند التحديد|بزرگ‌نمایی آیکون|Seçilince büyüt|Zoom de l'icône|Zoom del icono|Symbol-Zoom|Увеличение значка|Zoom do ícone
Ask before removing|تأكيد قبل الحذف|پرسیدن قبل از حذف|Kaldırmadan önce sor|Demander avant de retirer|Preguntar antes de quitar|Vor dem Entfernen fragen|Спрашивать перед удалением|Perguntar antes de remover
Menu order|ترتيب القائمة|ترتیب منو|Menü sırası|Ordre du menu|Orden del menú|Menü-Reihenfolge|Порядок меню|Ordem do menu
Open Glassio when the TV starts|افتح Glassio عند تشغيل التلفزيون|باز کردن Glassio با روشن شدن تلویزیون|TV açılınca Glassio'yu aç|Ouvrir Glassio au démarrage|Abrir Glassio al encender|Glassio beim Start öffnen|Открывать Glassio при включении|Abrir o Glassio ao ligar
Run setup again|تشغيل الإعداد من جديد|اجرای دوباره راه‌اندازی|Kurulumu yeniden çalıştır|Relancer la configuration|Repetir la configuración|Einrichtung erneut|Повторить настройку|Repetir configuração
Check for updates|البحث عن تحديثات|بررسی به‌روزرسانی|Güncellemeleri denetle|Rechercher des mises à jour|Buscar actualizaciones|Nach Updates suchen|Проверить обновления|Procurar atualizações
""".trim().lines().associate { l -> val p = l.split('|'); p[0] to p.drop(1) }
}
