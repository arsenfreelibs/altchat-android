# Работа с сабмодулем deltachat-core-rust

Rust-ядро (`jni/deltachat-core-rust`) — это сабмодуль, указывающий на отдельный репозиторий [https://github.com/chatmail/core](https://github.com/chatmail/core). Он используется совместно в Android и iOS проектах.

## Структура

```
deltachat-android/
└── jni/
    └── deltachat-core-rust/   ← git submodule → github.com/chatmail/core
```

## Внесение изменений в Rust-ядро

По умолчанию сабмодуль находится в состоянии **detached HEAD** (прикреплён к конкретному коммиту, а не к ветке). Перед началом работы нужно переключиться на ветку:

```bash
cd jni/deltachat-core-rust
git checkout main
# или создать новую ветку:
# git checkout -b my-feature
```

Затем вносишь изменения, коммитишь и пушишь:

```bash
# вносишь изменения...
git commit -am "описание изменений"
git push origin main
```

После этого в корне Android-проекта появится изменение — обновлённый указатель на коммит сабмодуля. Его нужно закоммитить:

```bash
cd /путь/к/deltachat-android
git add jni/deltachat-core-rust
git commit -m "update core submodule"
```

## Подтягивание изменений в iOS проект

iOS проект (deltachat-ios) использует тот же репозиторий `chatmail/core` как сабмодуль. Изменения **не подтягиваются автоматически** — каждый проект сам управляет своим указателем.

Чтобы обновить сабмодуль в iOS:

```bash
# в папке сабмодуля iOS-проекта:
cd <ios-submodule-path>
git pull origin main

# затем в корне iOS-проекта:
cd <ios-project-root>
git add <submodule-path>
git commit -m "update core submodule"
```

## Клонирование с инициализацией сабмодуля

```bash
git clone --recurse-submodules <url>

# или если уже склонировано:
git submodule update --init --recursive
```

## Обновление сабмодуля до последнего коммита remote

```bash
cd jni/deltachat-core-rust
git pull origin main

cd ../..
git add jni/deltachat-core-rust
git commit -m "update core submodule"
```



romanvalchuk@Romans-MacBook-Pro alt-chat-ios % cd deltachat-ios/libraries/deltachat-core-rust
romanvalchuk@Romans-MacBook-Pro deltachat-core-rust % git fetch
romanvalchuk@Romans-MacBook-Pro deltachat-core-rust % git pull origin develop