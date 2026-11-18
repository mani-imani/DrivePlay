<div dir="rtl">

# GeekMusic

**پلیر موزیک فلش و کارت حافظه با تم مشکی کامل، برای مانیتورهای اندرویدی ماشین**

[![Build](https://github.com/mani-imani/GeekMusic/actions/workflows/build.yml/badge.svg)](https://github.com/mani-imani/GeekMusic/actions/workflows/build.yml)
[![Release](https://img.shields.io/github/v/release/mani-imani/GeekMusic?label=release)](https://github.com/mani-imani/GeekMusic/releases/latest)
[![License: MIT](https://img.shields.io/badge/license-MIT-black.svg)](LICENSE)
[![Android 9](https://img.shields.io/badge/Android-9%20(API%2028)-3DDC84?logo=android&logoColor=white)](#سازگاری)

[English README](README.en.md) · برادرش: [GeekPlay](https://github.com/mani-imani/GeekPlay) (پلیر موزیک بلوتوث)

![GeekMusic — سوغاتی، هایده](docs/screenshot-fa.png)

GeekMusic جایگزین پلیر موزیک خود مانیتور ماشینه، با همون طراحی [GeekPlay](https://github.com/mani-imani/GeekPlay): پس‌زمینه‌ی مشکی کامل که شب چشم رو اذیت نکنه، دکمه‌های بزرگ و رنگی که از کاور هر آهنگ گرفته می‌شه. آهنگ‌ها مستقیم از فلش و کارت حافظه‌ای که به ماشین وصله پخش می‌شن.

## ویژگی‌ها

**پخش**
- نوار زمان قابل کشیدن، به‌علاوه‌ی دکمه‌های **۱۰ ثانیه جلو و عقب**
- پخش تصادفی و تکرار (بدون تکرار، تکرار همه، تکرار یک آهنگ)
- **ادامه‌ی پخش از همون ثانیه:** اگه موقع خاموش کردن ماشین آهنگ در حال پخش بوده، با روشن شدن ماشین یا وصل شدن فلش، پخش از همون‌جا ادامه پیدا می‌کنه
- کار با **دکمه‌های روی فرمون** (بعدی، قبلی، پخش و توقف)
- رفتار درست در تماس تلفنی و پیغام‌های مسیریاب: موسیقی قطع یا آروم می‌شه و بعد خودش ادامه پیدا می‌کنه
- اگه موزیک بلوتوث گوشی در حال پخش باشه، خودکار متوقف می‌شه تا دو صدا با هم پخش نشن

**کتابخونه**
- **پوشه‌بندی دقیقاً مثل خود فلش و کارت حافظه**، با مرتب‌سازی طبیعی (فایل `2` قبل از `10` میاد)
- همه‌ی آهنگ‌ها، خواننده‌ها و آلبوم‌ها
- **جستجو** توی اسم آهنگ، خواننده، آلبوم و اسم فایل. «ي» و «ی» یا «ك» و «ک» فرقی نمی‌کنن
- «پخش همه» و «پخش تصادفی» برای هر پوشه، خواننده یا آلبوم
- مینی‌پلیر پایین صفحه‌ی کتابخونه
- با زدن روی کاور یا اسم پوشه، کتابخونه دقیقاً توی پوشه‌ی آهنگ در حال پخش باز می‌شه

**ظاهر**
- مشکی کامل (`#000`)، حالت شب (۵۰٪ تیره‌تر) و فونت وزیرمتن با اعداد فارسی
- **کاور واقعی** آهنگ، از داخل خود فایل یا عکس `cover.jpg` و `folder.jpg` توی پوشه. رنگ صفحه از رنگ غالب کاور گرفته می‌شه
- برای آهنگ‌هایی که کاور ندارن، یه کاور اختصاصی ساخته می‌شه (همون الگوریتم GeekPlay)
- **درست کردن تگ‌های فارسی خراب:** تگ‌هایی که به‌جای فارسی به شکل `ÓæÛÇÊí` ذخیره شدن، درست نمایش داده می‌شن
- چیدمان خودش رو با اندازه‌ی صفحه تنظیم می‌کنه
- **تمام‌صفحه:** نوار بالای مانیتور پنهان می‌شه و با کشیدن یا لمس لبه‌ی بالای صفحه چند ثانیه نمایش داده می‌شه
- ساعت بالای کاور
- دکمه‌ها سمت چپ صفحه، یعنی سمت راننده، و کاور سمت راست

<div dir="ltr">

| فارسی | English |
|:---:|:---:|
| ![](docs/screenshot-fa.png) | ![](docs/screenshot-en.png) |

</div>

## سازگاری

| | |
|---|---|
| **ماشین** | جک S3 (JAC S3) |
| **مانیتور** | ZQ8003 · برد `ZQ_MTK_8007` · MediaTek |
| **اندروید** | ۸ به بالا، برای اندروید ۹ ساخته شده |
| **فرمت‌ها** | MP3 · FLAC · M4A/AAC · WAV · OGG · OPUS |

پخش و کتابخونه با ابزارهای استاندارد خود اندروید ساخته شدن و روی هر دستگاه اندرویدی کار می‌کنن. هماهنگی با منبع صدای مانیتور و دکمه‌های فرمون برای سرویس `car` همین خانواده از مانیتورها (`android.car.server`) نوشته شده. روی مانیتورهای دیگه این بخش بی‌سروصدا کنار گذاشته می‌شه.

## نصب

1. فایل `GeekMusic.apk` رو از [آخرین Release](https://github.com/mani-imani/GeekMusic/releases/latest) دانلود کن.
   لینک مستقیم: `https://github.com/mani-imani/GeekMusic/releases/latest/download/GeekMusic.apk`
2. روی مانیتور نصبش کن. می‌تونی مانیتور رو به هات‌اسپات گوشی وصل کنی و لینک رو توی مرورگرش باز کنی، یا APK رو با فلش منتقل کنی.
3. بار اول که اپ رو باز می‌کنی، اجازه‌ی دسترسی به فایل‌ها رو بده.

## چطور کار می‌کنه

اپ موزیک خود مانیتور (`android.car.app.media`) رو باز کردیم و بررسی کردیم. پخش رو با `MediaPlayer` استاندارد اندروید انجام می‌ده و آهنگ‌ها رو از مسیرهای `/storage` پیدا می‌کنه. GeekMusic هم از همین ابزارها استفاده می‌کنه. تنها بخش مخصوص این مانیتور، هماهنگی با سرویس سیستمی `car` بود:

- وقتی یه اپ دیگه موسیقی پخش می‌کنه، سرویس `car` منبع صدای مانیتور رو روی حالت «اپ دیگه» (`EmptySource`) می‌ذاره. GeekMusic موقع شروع پخش همین درخواست رو خودش می‌فرسته. این‌طوری صدای رادیو یا بلوتوث قطع می‌شه و دکمه‌های فرمون به شکل کلیدهای استاندارد مدیا به اپ می‌رسن.
- دکمه‌های فرمون رو یه `MediaSession` می‌گیره. اگه اپ روی صفحه باز باشه، خود اپ مستقیم جوابشون رو می‌ده.

کد این بخش توی [`CarSource.java`](app/src/main/java/ir/maniimani/geekmusic/CarSource.java) هست. روش کلی پیدا کردن این پروتکل توی README پروژه‌ی [GeekPlay](https://github.com/mani-imani/GeekPlay#چطور-کار-میکنه) توضیح داده شده.

## ساخت از سورس

با هر push به `main`، GitHub Actions اپ رو می‌سازه. اگه `versionName` عوض شده باشه، APK خودکار توی Releases هم منتشر می‌شه.

<div dir="ltr">

```bash
# JDK 17 + Android SDK (compileSdk 34) + Gradle 8.7
gradle assembleRelease
```

</div>

برای انتشار یه نسخه‌ی جدید، `versionName` و `versionCode` رو توی `app/build.gradle` بالا ببر، یادداشت‌های نسخه رو با عنوان `## x.y.z` به `CHANGELOG.md` اضافه کن و push کن.

> کلید امضا (`app/geekmusic.keystore`) عمداً توی ریپو گذاشته شده تا هر نسخه‌ی جدید روی نسخه‌ی قبلی نصب بشه. این اپ برای نصب دستیه و این کلید برای Google Play در نظر گرفته نشده.

## ساختار پروژه

<div dir="ltr">

```
app/src/main/java/ir/maniimani/geekmusic/
├── MainActivity.java       Now-playing screen, layout, wiring
├── LibraryPanel.java       Folders / songs / artists / albums / search + mini player
├── PlayerService.java      MediaPlayer, queue, shuffle/repeat, audio focus, MediaSession, resume
├── Library.java            Storage discovery, folder tree, MediaStore + tag reading, tag cache
├── CarSource.java          Hand-over of audio source / steering keys on the head unit
├── Text.java               Persian digits, search normalisation, natural sort, tag repair
├── Art.java                Generated covers + palette from real artwork
├── ArtworkView.java        Rounded cover with cross-fade
├── SeekView.java           Draggable seek bar
├── SkipButton.java         ±10 s buttons
├── PlayButton.java         Always-circular play/pause
├── GlowView.java           Black background with a soft coloured bloom
├── Thumbs.java             Cached list thumbnails
├── Track.java · Folder.java
└── AutoResumeReceiver.java Resume after ignition / USB mount
```

</div>

## ساخته‌شده با Claude

این پروژه، مثل GeekPlay، از ایده تا نسخه‌ی نهایی با کمک **[Claude](https://claude.ai)** ساخته شد، دستیار هوش مصنوعی شرکت Anthropic، روی اشتراک **Claude Pro**. Claude توی همه‌ی مراحل نقش داشت: بررسی کد پلیر و سرویس سیستمی خود مانیتور، طراحی رابط کاربری، نوشتن کد پلیر و کتابخونه و راه‌اندازی بیلد خودکار. تست روی ماشین واقعی و تصمیم‌های طراحی هم کار خودم بود.

## سازنده

**مانی ایمانی‌سنجر** · [GeekMani](https://t.me/geekmani)
[maniimani.ir](https://maniimani.ir) · [GitHub](https://github.com/mani-imani) · [LinkedIn](https://linkedin.com/in/mani-imani)

## مجوز

کد با مجوز [MIT](LICENSE) منتشر شده. فونت [وزیرمتن](https://github.com/rastikerdar/vazirmatn) با مجوز SIL OFL 1.1 همراه اپ قرار گرفته.

این پروژه مستقله و هیچ ارتباطی با سازنده‌ی مانیتور یا خودروسازی جک نداره. عکس‌های این صفحه پیش‌نمایش رابط کاربری‌ان، و اسم آهنگ‌ها فقط برای نمونه آورده شدن.

</div>
