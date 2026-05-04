# 🎙️ Voice Commander

**Offline voice assistant for abbreviation commands**  
**Офлайн голосовой ассистент для команд-аббревиатур**  
**Офлайн голосовий асистент для команд-абревіатур**

![Kotlin](https://img.shields.io/badge/Kotlin-7F52FF?style=flat&logo=kotlin&logoColor=white)
![Android](https://img.shields.io/badge/Android-3DDC84?style=flat&logo=android&logoColor=white)
![Jetpack Compose](https://img.shields.io/badge/Jetpack%20Compose-4285F4?style=flat&logo=jetpackcompose&logoColor=white)
![Vosk](https://img.shields.io/badge/Vosk-ASR-orange?style=flat)
![Offline](https://img.shields.io/badge/Offline-100%25-green?style=flat)
![License](https://img.shields.io/badge/License-MIT-blue?style=flat)

---

[![EN](https://img.shields.io/badge/🇬🇧-English-4285F4?style=for-the-badge)](#en)
[![RU](https://img.shields.io/badge/🇷🇺-Русский-CC0000?style=for-the-badge)](#ru)
[![UA](https://img.shields.io/badge/🇺🇦-Українська-FFD700?style=for-the-badge)](#ua)

---

<a name="en"></a>
## 🇬🇧 EN

### What is this?

Voice Commander is a fully offline Android app that recognizes short abbreviation commands (e.g. `ВГВКМ`, `ВКРОР`) and converts them into coded responses via TTS (text-to-speech).

No internet. No cloud. Works in noisy environments — military comms, industrial control, accessibility devices.

> Swap Vosk model and `dictionary.json` to use any language.

### How it works

```
Speak abbreviation → Vosk recognizes → Two-pass search → TTS speaks the result
```

**Pass 1** — prefix lookup (first 3 characters)  
**Pass 2** — exact match in main dictionary  
**Result** — code spoken digit by digit: `13322` → *"one · three · three · two · two"*

### Tech stack

| Component | Technology |
|---|---|
| UI | Jetpack Compose + Material 3 |
| Speech recognition | Vosk (offline, ru-0.22) |
| Text-to-speech | Android TTS |
| Language | Kotlin |
| Min SDK | Android 8.0 (API 26) |
| Internet required | ❌ Never |

### Dictionary format

Main dictionary (`dictionary.json`):
```json
{
  "ВГВКМ": "13322",
  "ВКРОР": "41124",
  "ВКВГТ": "32212"
}
```

Prefix dictionary (`prefix.json`):
```json
{
  "ВГВ": ["ВГВКМ"],
  "ВКР": ["ВКРОР", "ВКРВК"]
}
```

### Setup

1. Clone the repository
2. Open in Android Studio
3. Place Vosk Russian model into `app/src/main/assets/model/`  
   → Download: [alphacephei.com/vosk/models](https://alphacephei.com/vosk/models) (vosk-model-small-ru)
4. Add your `dictionary.json` and `prefix.json` to `app/src/main/assets/`
5. Build and run

### Where it's useful

- 🏭 **Industrial / SCADA** — voice control without internet
- 🎖️ **Military / special comms** — short coded commands
- ♿ **Accessibility (AAC)** — voice shortcuts for people with limited mobility
- 🏥 **Medical / emergency** — fast triage codes
- 📦 **Warehouse / logistics** — pick-by-voice operations
- 🎓 **Training / QA** — voice-driven test scripts
- 🔒 **Air-gapped systems** — zero network dependency

---

<a name="ru"></a>
## 🇷🇺 RU

### Что это?

Voice Commander — полностью офлайн Android-приложение, которое распознаёт короткие команды-аббревиатуры (например `ВГВКМ`, `ВКРОР`) и преобразует их в кодовые ответы через TTS.

Без интернета. Без облака. Работает в шумных условиях — производство, военная связь, системы управления, средства реабилитации.

> Замените модель Vosk и `dictionary.json` для работы с любым языком.

### Как работает

```
Произнести аббревиатуру → Vosk распознаёт → Двухпроходный поиск → TTS озвучивает результат
```

**Проход 1** — поиск по префиксу (первые 3 символа)  
**Проход 2** — точное совпадение в основном словаре  
**Результат** — код озвучивается по цифрам: `13322` → *«один · три · три · два · два»*

### Стек

| Компонент | Технология |
|---|---|
| UI | Jetpack Compose + Material 3 |
| Распознавание речи | Vosk (офлайн, ru-0.22) |
| Озвучивание | Android TTS |
| Язык | Kotlin |
| Мин. версия Android | 8.0 (API 26) |
| Интернет | ❌ Не нужен |

### Установка

1. Клонировать репозиторий
2. Открыть в Android Studio
3. Положить модель Vosk в `app/src/main/assets/model/`  
   → Скачать: [alphacephei.com/vosk/models](https://alphacephei.com/vosk/models) (vosk-model-small-ru)
4. Добавить `dictionary.json` и `prefix.json` в `app/src/main/assets/`
5. Собрать и запустить

### Применение

- 🏭 **Промышленность / SCADA** — голосовое управление без интернета
- 🎖️ **Военная / спецсвязь** — короткие кодовые команды
- ♿ **Доступная среда (AAC)** — голосовые команды для людей с ограниченной подвижностью
- 🏥 **Медицина / экстренные службы** — быстрые коды триажа
- 📦 **Склад / логистика** — голосовое управление операциями
- 🎓 **Обучение / контроль качества** — голосовые тестовые сценарии
- 🔒 **Изолированные системы** — без зависимости от сети

---

<a name="ua"></a>
## 🇺🇦 UA

### Що це?

Voice Commander — повністю офлайн Android-застосунок, який розпізнає короткі команди-абревіатури (наприклад `ВГВКМ`, `ВКРОР`) і перетворює їх на кодові відповіді через TTS.

Без інтернету. Без хмари. Працює в галасливих умовах — виробництво, військовий зв'язок, системи управління, засоби реабілітації.

> Замініть модель Vosk і `dictionary.json` для роботи з будь-якою мовою.

### Як працює

```
Вимовити абревіатуру → Vosk розпізнає → Двопрохідний пошук → TTS озвучує результат
```

**Прохід 1** — пошук за префіксом (перші 3 символи)  
**Прохід 2** — точний збіг в основному словнику  
**Результат** — код озвучується по цифрах: `13322` → *«один · три · три · два · два»*

### Стек

| Компонент | Технологія |
|---|---|
| UI | Jetpack Compose + Material 3 |
| Розпізнавання мовлення | Vosk (офлайн, ru-0.22) |
| Озвучування | Android TTS |
| Мова | Kotlin |
| Мін. версія Android | 8.0 (API 26) |
| Інтернет | ❌ Не потрібен |

### Встановлення

1. Клонувати репозиторій
2. Відкрити в Android Studio
3. Покласти модель Vosk у `app/src/main/assets/model/`  
   → Завантажити: [alphacephei.com/vosk/models](https://alphacephei.com/vosk/models) (vosk-model-small-ru)
4. Додати `dictionary.json` і `prefix.json` у `app/src/main/assets/`
5. Зібрати та запустити

### Застосування

- 🏭 **Промисловість / SCADA** — голосове керування без інтернету
- 🎖️ **Військовий / спецзв'язок** — короткі кодові команди
- ♿ **Доступне середовище (AAC)** — голосові команди для людей з обмеженою рухливістю
- 🏥 **Медицина / екстрені служби** — швидкі коди тріажу
- 📦 **Склад / логістика** — голосове керування операціями
- 🎓 **Навчання / контроль якості** — голосові тестові сценарії
- 🔒 **Ізольовані системи** — без залежності від мережі

---

## Author

**Nikolay Shikin** — freelance developer & designer  
🌐 [shikinn.com](https://shikinn.com)  
💼 [freelance.ru/guru_sun](https://freelance.ru/guru_sun)  
🐙 [github.com/550953](https://github.com/550953)

---

*MIT License · Made in Ukraine 🇺🇦*
