package com.glasslauncher.tv.data

/**
 * App language (System → Language). English is the source. Each other language has its words in two places: the
 * small table below (written by hand, it comes first) and `assets/i18n/<code>.tsv` (every text of the screens:
 * "English<TAB>translation" a line, a line break written as \n), read when the language is chosen. Whatever is
 * in neither stays in English: a sentence put together from parts (a number, a name inside it) most of all.
 *
 * The screens need no call of their own: `ui.Text` looks every text up here.
 */
object I18n {
    /** Set once by the app: the language's file is read from its assets. Without it (unit tests) only the table counts. */
    @Volatile var assets: android.content.res.AssetManager? = null
    @Volatile var lang: String = "en"
        set(v) { val was = field; field = v; load(v); if (was != v) rev.intValue++ }
    /** Counts the changes of language: a text on screen reads it, so it is drawn again in the new language at once. */
    val rev = androidx.compose.runtime.mutableIntStateOf(0)

    /** Code → its own name, in the order the setting cycles through. */
    val LANGS = linkedMapOf(
        "en" to "English", "ar" to "العربية", "fa" to "فارسی", "tr" to "Türkçe", "fr" to "Français",
        "es" to "Español", "de" to "Deutsch", "ru" to "Русский", "pt" to "Português",
    )
    private val COLS = listOf("ar", "fa", "tr", "fr", "es", "de", "ru", "pt")

    /** The chosen language's words from its file, and the language they are for. */
    @Volatile private var words: Map<String, String> = emptyMap()
    @Volatile private var wordsOf = "en"

    /** Reads the language's file (a few hundred KB at most; once, when the app starts or the language changes). */
    private fun load(code: String) {
        val a = assets ?: return
        if (code == wordsOf) return
        words = if (code == "en") emptyMap() else runCatching {
            a.open("i18n/$code.tsv").bufferedReader().useLines { lines -> parse(lines) }
        }.onFailure { Glog.w("Language $code", it) }.getOrDefault(emptyMap())
        wordsOf = code
    }

    fun parse(lines: Sequence<String>): Map<String, String> {
        val m = HashMap<String, String>(2048)
        for (l in lines) {
            val i = l.indexOf('\t')
            if (i <= 0 || i == l.lastIndex) continue
            m[l.substring(0, i).replace("\\n", "\n")] = l.substring(i + 1).replace("\\n", "\n")
        }
        return m
    }

    private fun find(s: String, col: Int): String? = TABLE[s]?.getOrNull(col)?.takeIf { it.isNotBlank() } ?: words[s]

    fun tr(s: String): String {
        if (lang == "en" || s.isBlank()) return s
        val col = COLS.indexOf(lang).takeIf { it >= 0 } ?: return s
        find(s, col)?.let { return it }
        // A row under another ("  · Name"), a link ("→ Name"), or the same words with spaces around them.
        if (s.startsWith("  · ")) find(s.removePrefix("  · "), col)?.let { return "  · $it" }
        if (s.startsWith("→ ")) find(s.removePrefix("→ "), col)?.let { return "→ $it" }
        val t = s.trim()
        if (t.length != s.length) find(t, col)?.let { return s.replace(t, it) }
        return s
    }

    // English | Arabic | Persian | Turkish | French | Spanish | German | Russian | Portuguese
    private val TABLE: Map<String, List<String>> = """
Settings|الإعدادات|تنظیمات|Ayarlar|Réglages|Ajustes|Einstellungen|Настройки|Ajustes
Connections|الاتصالات|اتصال‌ها|Bağlantılar|Connexions|Conexiones|Verbindungen|Подключения|Conexões
Catalogs & add-ons|الكتالوجات والإضافات
Add-ons|الإضافات
Carousel catalogs|كتالوجات العرض الدوّار
Catalogs in the carousel|الكتالوجات في العرض الدوّار
In the carousel|في العرض الدوّار
Your add-ons|إضافاتك
Add an add-on|إضافة إضافة جديدة
Add-on|إضافة
No add-ons yet|لا توجد إضافات بعد
Add from my phone (QR)|إضافة من هاتفي (QR)
Add add-on link|إضافة رابط إضافة
Connect to Nuvio|الاتصال بـ Nuvio
Remove add-on|إزالة الإضافة
From your Nuvio account|من حساب Nuvio الخاص بك
Add-ons from my Nuvio account|إضافات من حساب Nuvio الخاص بي
Catalogs from Stremio's add-ons|كتالوجات من إضافات Stremio
Refresh catalogs|تحديث الكتالوجات
Select all catalogs|تحديد كل الكتالوجات
Select none|إلغاء تحديد الكل
Catalogs for apps|كتالوجات التطبيقات
Apps and their catalogs|التطبيقات وكتالوجاتها
Continue Watching in app carousels|متابعة المشاهدة في عروض التطبيقات الدوّارة
Play opens the app|Play يفتح التطبيق
Play opens the streaming app|Play يفتح تطبيق البث
Find links with JustWatch|البحث عن الروابط عبر JustWatch
Country|الدولة
Accounts & profiles|الحسابات والملفات الشخصية|حساب‌ها و پروفایل‌ها|Hesaplar ve profiller|Comptes et profils|Cuentas y perfiles|Konten & Profile|Аккаунты и профили|Contas e perfis
Continue Watching & Library|متابعة المشاهدة والمكتبة|ادامه تماشا و کتابخانه|İzlemeye devam ve Kitaplık|Reprendre et Bibliothèque|Seguir viendo y Biblioteca|Weiterschauen & Mediathek|Продолжить и Библиотека|Continuar e Biblioteca
Look & menu|المظهر والقائمة|ظاهر و منو|Görünüm ve menü|Apparence et menu|Aspecto y menú|Aussehen & Menü|Вид и меню|Aparência e menu
Background & screensaver|الخلفية وشاشة التوقف|پس‌زمینه و محافظ صفحه|Arka plan ve ekran koruyucu|Fond et économiseur|Fondo y salvapantallas|Hintergrund & Bildschirmschoner|Фон и заставка|Fundo e proteção de tela
Music & app rows|الموسيقى وصفوف التطبيقات|موسیقی و ردیف برنامه‌ها|Müzik ve uygulama satırları|Musique et rangées d'apps|Música y filas de apps|Musik & App-Reihen|Музыка и ряды приложений|Música e filas de apps
Smart home|المنزل الذكي|خانه هوشمند|Akıllı ev|Maison connectée|Hogar inteligente|Smart Home|Умный дом|Casa inteligente
Other accounts|حسابات أخرى
Profiles|الملفات الشخصية
Profile|الملف الشخصي
At start|عند البدء
Name|الاسم
Picture|الصورة
Keep accounts the same|إبقاء الحسابات متطابقة
Where it comes from|من أين يأتي
Sources|المصادر
Clear the list|مسح القائمة
Library look|مظهر المكتبة
Lists in the Library|القوائم في المكتبة
+ saves to|+ يحفظ في
Library rows|صفوف المكتبة
Platform rows|صفوف المنصات
Services|الخدمات
Slides|الشرائح
What it shows|ما يعرضه
Title & info|العنوان والمعلومات
Buttons|الأزرار
Dots|النقاط
Pictures|الصور
Trailers|الإعلانات التشويقية
Place & size|المكان والحجم
Dock glass|زجاج شريط التطبيقات
Glass style|نمط الزجاج
Arrange apps|ترتيب التطبيقات
Icons|الأيقونات
Install an icon pack|تثبيت حزمة أيقونات
Extra icons|أيقونات إضافية
Inputs|المداخل
Glass|زجاج
Shapes & text|الأشكال والنص
Selection|التحديد
Behind pages|خلف الصفحات
Top bar|الشريط العلوي
Clock & weather|الساعة والطقس
Menu|القائمة
Menu items|عناصر القائمة
TV info|معلومات التلفاز
Photos|الصور
Your pictures & videos|صورك وفيديوهاتك
My pictures & videos|صوري وفيديوهاتي
Screensaver videos as background|فيديوهات شاشة التوقف كخلفية
Over the background|فوق الخلفية
Large clock|ساعة كبيرة
Info over photos|المعلومات فوق الصور
Screensaver · When|شاشة التوقف · التوقيت
Screensaver · What plays|شاشة التوقف · ما يُعرض
Screensaver · Trailers|شاشة التوقف · الإعلانات التشويقية
Screensaver · Videos|شاشة التوقف · الفيديوهات
Screensaver · Locked videos|شاشة التوقف · الفيديوهات المقفلة
Screensaver · On screen|شاشة التوقف · على الشاشة
Screensaver · Also on it|شاشة التوقف · وعليها أيضًا
Collections|المجموعات
My videos|فيديوهاتي
Music|الموسيقى
Music apps|تطبيقات الموسيقى
Music island|جزيرة الموسيقى
Island|الجزيرة
App rows (Channels)|صفوف التطبيقات (القنوات)
Apps with channels|تطبيقات لها قنوات
Page background|خلفية الصفحة
Hue lights|إضاءات Hue
Bridge|الجسر
Lights & areas|الإضاءات والمناطق
Hue colours|ألوان Hue
Hue follows the selection|Hue يتبع التحديد
Hue · Afterwards|Hue · بعد ذلك
Corner camera|كاميرا الزاوية
Multi-view|العرض المتعدد
Doorbell|جرس الباب
Pop-up look|مظهر النافذة المنبثقة
Home Assistant link|رابط Home Assistant
Home button & start|زر Home والتشغيل
Remote buttons|أزرار جهاز التحكم
Other TVs & backups|أجهزة تلفاز أخرى ونسخ احتياطية
Sync between TVs|المزامنة بين أجهزة التلفاز
App lock|قفل التطبيقات
TV|التلفاز
TV access (ADB)|الوصول إلى التلفاز (ADB)
New|جديد|جدید|Yeni|Nouveau|Nuevo|Neu|Новое|Novo
Changed|المُغيَّرة|تغییرکرده|Değişen|Modifié|Cambiado|Geändert|Изменено|Alterado
New settings|إعدادات جديدة
Changed settings|الإعدادات المُغيَّرة
Default|افتراضي
back to|العودة إلى
Back to default|العودة إلى الافتراضي
Go to it|الانتقال إليه
Cancel|إلغاء
On|تشغيل
Off|إيقاف
Mark them all as seen|تعليمها جميعًا كمقروءة
Every setting is as it comes|كل الإعدادات كما هي افتراضيًا
Catalogs|الكتالوجات|کاتالوگ‌ها|Kataloglar|Catalogues|Catálogos|Kataloge|Каталоги|Catálogos
Continue Watching|متابعة المشاهدة|ادامه تماشا|İzlemeye devam et|Reprendre|Seguir viendo|Weiterschauen|Продолжить просмотр|Continuar assistindo
Library|المكتبة|کتابخانه|Kitaplık|Bibliothèque|Biblioteca|Mediathek|Библиотека|Biblioteca
Carousel|العرض الدوّار|کاروسل|Karusel|Carrousel|Carrusel|Karussell|Карусель|Carrossel
Dock & icons|شريط التطبيقات والأيقونات|داک و آیکون‌ها|Dock ve simgeler|Dock et icônes|Dock e iconos|Dock & Symbole|Док и значки|Dock e ícones
Menu & themes|القائمة والسمات|منو و پوسته‌ها|Menü ve temalar|Menu et thèmes|Menú y temas|Menü & Designs|Меню и темы|Menu e temas
Background & glass|الخلفية والزجاج|پس‌زمینه و شیشه|Arka plan ve cam|Fond et verre|Fondo y cristal|Hintergrund & Glas|Фон и стекло|Fundo e vidro
Screensaver|شاشة التوقف|محافظ صفحه|Ekran koruyucu|Économiseur d'écran|Salvapantallas|Bildschirmschoner|Заставка|Protetor de tela
Cameras|الكاميرات|دوربین‌ها|Kameralar|Caméras|Cámaras|Kameras|Камеры|Câmeras
Performance|الأداء|کارایی|Performans|Performances|Rendimiento|Leistung|Производительность|Desempenho
Manage from phone|التحكم عبر الهاتف|مدیریت با گوشی|Telefondan yönet|Gérer depuis le téléphone|Gestionar desde el móvil|Vom Handy verwalten|Управление с телефона|Gerir pelo telemóvel
System|النظام|سیستم|Sistem|Système|Sistema|System|Система|Sistema
About|حول|درباره|Hakkında|À propos|Acerca de|Über|О программе|Sobre
Glassio settings|إعدادات Glassio|تنظیمات Glassio|Glassio ayarları|Réglages Glassio|Ajustes de Glassio|Glassio-Einstellungen|Настройки Glassio|Ajustes do Glassio
Android settings|إعدادات Android|تنظیمات اندروید|Android ayarları|Réglages Android|Ajustes de Android|Android-Einstellungen|Настройки Android|Ajustes do Android
Themes|السمات|پوسته‌ها|Temalar|Thèmes|Temas|Designs|Темы|Temas
Camera|الكاميرا|دوربین|Kamera|Caméra|Cámara|Kamera|Камера|Câmera
Background|الخلفية|پس‌زمینه|Arka plan|Arrière-plan|Fondo|Hintergrund|Фон|Fundo
Wi-Fi|Wi-Fi|وای‌فای|Wi-Fi|Wi-Fi|Wi-Fi|WLAN|Wi-Fi|Wi-Fi
Apps|التطبيقات|برنامه‌ها|Uygulamalar|Apps|Apps|Apps|Приложения|Apps
Hello|مرحبًا|سلام|Merhaba|Bonjour|Hola|Hallo|Привет|Olá
Let's connect and customise your Glassio|لنربط Glassio ونخصصه|بیایید Glassio را وصل و شخصی کنیم|Glassio'nu bağlayıp kişiselleştirelim|Connectons et personnalisons votre Glassio|Conectemos y personalicemos tu Glassio|Lass uns dein Glassio verbinden und anpassen|Давайте подключим и настроим Glassio|Vamos ligar e personalizar o seu Glassio
Get started|ابدأ|شروع|Başla|Commencer|Empezar|Los geht's|Начать|Começar
Restore a backup|استعادة نسخة احتياطية|بازیابی پشتیبان|Yedeği geri yükle|Restaurer une sauvegarde|Restaurar copia|Backup wiederherstellen|Восстановить копию|Restaurar cópia
Later|لاحقًا|بعداً|Sonra|Plus tard|Más tarde|Später|Позже|Mais tarde
Choose a look|اختر مظهرًا|ظاهر را انتخاب کنید|Bir görünüm seç|Choisissez un style|Elige un estilo|Wähle einen Look|Выберите вид|Escolha um visual
Connect|اتصال|اتصال|Bağlan|Connecter|Conectar|Verbinden|Подключить|Ligar
Next|التالي|بعدی|İleri|Suivant|Siguiente|Weiter|Далее|Seguinte
You're all set|كل شيء جاهز|همه چیز آماده است|Her şey hazır|Tout est prêt|Todo listo|Alles bereit|Всё готово|Tudo pronto
Start|ابدأ|شروع|Başla|Démarrer|Empezar|Start|Старт|Iniciar
Back|رجوع|بازگشت|Geri|Retour|Atrás|Zurück|Назад|Voltar
Done|تم|تمام|Bitti|Terminé|Listo|Fertig|Готово|Concluído
Saved|تم الحفظ|ذخیره‌شده|Kaydedilenler|Enregistrés|Guardados|Gemerkt|Сохранённое|Guardados
Open apps|التطبيقات المفتوحة|برنامه‌های باز|Açık uygulamalar|Apps ouvertes|Apps abiertas|Offene Apps|Открытые приложения|Apps abertas
No apps are open|لا توجد تطبيقات مفتوحة|هیچ برنامه‌ای باز نیست|Açık uygulama yok|Aucune app ouverte|No hay apps abiertas|Keine Apps offen|Нет открытых приложений|Nenhuma app aberta
Language|اللغة|زبان|Dil|Langue|Idioma|Sprache|Язык|Idioma
Theme|السمة|پوسته|Tema|Thème|Tema|Design|Тема|Tema
Picture quality|جودة الصورة|کیفیت تصویر|Resim kalitesi|Qualité d'image|Calidad de imagen|Bildqualität|Качество изображения|Qualidade de imagem
Shuffle all catalogs|ترتيب عشوائي لكل الكتالوجات|ترکیب همه کاتالوگ‌ها|Tüm katalogları karıştır|Mélanger tous les catalogues|Mezclar todos los catálogos|Alle Kataloge mischen|Перемешать все каталоги|Misturar todos os catálogos
Surprise me|فاجئني|غافلگیرم کن|Beni şaşırt|Surprends-moi|Sorpréndeme|Überrasch mich|Удиви меня|Surpreende-me
White logo|شعار أبيض|لوگوی سفید|Beyaz logo|Logo blanc|Logo blanco|Weißes Logo|Белый логотип|Logótipo branco
Back up now|نسخ احتياطي الآن|پشتیبان‌گیری اکنون|Şimdi yedekle|Sauvegarder|Copiar ahora|Jetzt sichern|Создать копию|Fazer cópia agora
Restore backup|استعادة النسخة الاحتياطية|بازیابی پشتیبان|Yedeği geri yükle|Restaurer|Restaurar copia|Wiederherstellen|Восстановить|Restaurar cópia
Hold Home|الضغط المطول على Home|نگه داشتن Home|Home'u basılı tut|Maintenir Home|Mantener Home|Home gedrückt halten|Удерживать Home|Manter Home
Glow|توهج|درخشش|Parıltı|Halo|Brillo|Leuchten|Свечение|Brilho
Icon zoom on focus|تكبير الأيقونة عند التحديد|بزرگ‌نمایی آیکون|Seçilince büyüt|Zoom de l'icône|Zoom del icono|Symbol-Zoom|Увеличение значка|Zoom do ícone
Ask before removing|السؤال قبل الإزالة|پرسیدن قبل از حذف|Kaldırmadan önce sor|Demander avant de retirer|Preguntar antes de quitar|Vor dem Entfernen fragen|Спрашивать перед удалением|Perguntar antes de remover
Menu order|ترتيب القائمة|ترتیب منو|Menü sırası|Ordre du menu|Orden del menú|Menü-Reihenfolge|Порядок меню|Ordem do menu
Open Glassio when the TV starts|فتح Glassio عند تشغيل التلفاز|باز کردن Glassio با روشن شدن تلویزیون|TV açılınca Glassio'yu aç|Ouvrir Glassio au démarrage|Abrir Glassio al encender|Glassio beim Start öffnen|Открывать Glassio при включении|Abrir o Glassio ao ligar
Run setup again|تشغيل الإعداد مرة أخرى|اجرای دوباره راه‌اندازی|Kurulumu yeniden çalıştır|Relancer la configuration|Repetir la configuración|Einrichtung erneut|Повторить настройку|Repetir configuração
Check for updates|التحقق من التحديثات|بررسی به‌روزرسانی|Güncellemeleri denetle|Rechercher des mises à jour|Buscar actualizaciones|Nach Updates suchen|Проверить обновления|Procurar atualizações
""".trim().lines().associate { l -> val p = l.split('|'); p[0] to p.drop(1) }
}
