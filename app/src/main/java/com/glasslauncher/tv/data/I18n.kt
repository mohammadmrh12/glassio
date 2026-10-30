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
Catalogs|الكتالوجات|کاتالوگ‌ها|Kataloglar|Catalogues|Catálogos|Kataloge|Каталоги|Catálogos
Continue Watching|متابعة المشاهدة|ادامه تماشا|İzlemeye devam et|Reprendre|Seguir viendo|Weiterschauen|Продолжить просмотр|Continuar assistindo
Library|المكتبة|کتابخانه|Kitaplık|Bibliothèque|Biblioteca|Mediathek|Библиотека|Biblioteca
Carousel|الكاروسيل|کاروسل|Karusel|Carrousel|Carrusel|Karussell|Карусель|Carrossel
Dock & icons|الشريط والأيقونات|داک و آیکون‌ها|Dock ve simgeler|Dock et icônes|Dock e iconos|Dock & Symbole|Док и значки|Dock e ícones
Menu & themes|القائمة والثيمات|منو و پوسته‌ها|Menü ve temalar|Menu et thèmes|Menú y temas|Menü & Designs|Меню и темы|Menu e temas
Clock & glass|الساعة والزجاج|ساعت و شیشه|Saat ve cam|Horloge et verre|Reloj y cristal|Uhr & Glas|Часы и стекло|Relógio e vidro
Screensaver|شاشة التوقف|محافظ صفحه|Ekran koruyucu|Économiseur d'écran|Salvapantallas|Bildschirmschoner|Заставка|Protetor de tela
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
