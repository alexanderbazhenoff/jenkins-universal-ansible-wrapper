<!-- markdownlint-disable MD033 MD041 -->
<div align='center'>
<!-- docs-ci-cut-begin -->

# Jenkins Universal Wrapper Pipeline

Быстрый и простой способ создавать Jenkins pipeline'ы через конфигурационные yaml файлы.

[![Super-Linter](https://github.com/alexanderbazhenoff/jenkins-universal-wrapper-pipeline/actions/workflows/super-linter.yml/badge.svg?branch=main)](https://github.com/marketplace/actions/super-linter)
[![Release CI](https://github.com/alexanderbazhenoff/jenkins-universal-wrapper-pipeline/actions/workflows/release-ci.yml/badge.svg?branch=main)](CHANGELOG.md)
[![GitHub Release](https://img.shields.io/github/v/release/alexanderbazhenoff/jenkins-universal-wrapper-pipeline)](https://github.com/alexanderbazhenoff/jenkins-universal-wrapper-pipeline/releases)
[![GitHub License](https://img.shields.io/github/license/alexanderbazhenoff/jenkins-universal-wrapper-pipeline)](LICENSE)
[![PRs Welcome](https://img.shields.io/badge/PRs-welcome-brightgreen.svg?style=flat-square)](https://makeapullrequest.com)
[![Tweet](https://img.shields.io/twitter/url/http/shields.io.svg?style=social)](https://twitter.com/intent/tweet?text=Create+your+pipelines+easier+and+faster%21%20&url=https://github.com/alexanderbazhenoff/jenkins-universal-wrapper-pipeline&hashtags=devops,cicd,jenkins,ansible,yaml)

<span style="font-size:0.8em;">[English](README.md) • [**Russian**](README_RUS.md)</span>
</div>
<!-- docs-ci-cut-end -->

## Описание

Jenkins Universal Wrapper Pipeline позволяет создавать multistage pipeline'ы с помощью описания действий в yaml файлах.
Для их написания не требуются навыки программирования Groovy (включая декларативный стиль pipeline'ов). Нужно просто
создать конфигурационный файл и описать в нем все стадии и действия, которые необходимо выполнить.

## Основные функции

- Встроенное получение исходников другого проекта (git).
- Встроенная установка ansible-коллекций.
- Встроенная отправка отчетов (email, или mattermost).
- Запуск ansible playbook'ов, разместив код в описание действия внутри конфигурационного файла. Вы можете так же
  запускать все, что нужно как скрипт: puppet, terraform и т.д.
- Выбор ноды и перемещение необходимых файлов между ними.
- Работа с файлами-артефактами.
- Возможность запуска действий в стадиях (stages) в параллель, или последовательно.
- Добавление параметров pipeline'а при первом запуске с помощью их указания внутри конфигурационного файла.
- Вы можете так же расширять функционал pipeline, запуская нативный код (например, Groovy для Jenkins), как "часть
  pipeline" с помощью добавления кода в действия (actions) pipeline'а внутри конфигурационного файла.

## Требования

1. Jenkins версии 2.x, или выше (возможно, версии ниже так же подойдут, но тестировалось на версиях 2.190.x).
2. [Linux ноды Jenkins](https://www.jenkins.io/doc/book/installing/linux/) для запуска pipeline. Большинство
   "встроенных" в pipeline действий за исключением запуска скриптов и ansible-playbook'ов (таких, как получение
   исходников, перемещение файлов между нодами, выбор ноды, работа с файлами-артефактами, добавление параметров
   pipeline, запуск кода "как часть pipeline"), скорей всего, так же работаю на Windows node'ах, но не тестировалось.
   Запуск на Windows node'ах bat и Powershell в настоящий момент не поддерживается. Запуск terraform и puppet может
   быть осуществлен через сохранение необходимых файлов и запуск внутри скрипта, подобно тому, как вы осуществляете их
   запуск через командную строку.
3. Этот pipeline требует подключения
   [jenkins shared library](https://github.com/alexanderbazhenoff/jenkins-shared-library).
4. [AnsiColor Jenkins plugin](https://plugins.jenkins.io/ansicolor/) для цветного вывода в консоль.
5. Для запуска ansible может так же потребоваться установка
   [Ansible Jenkins plugin](https://plugins.jenkins.io/ansible/) (опционально, в настоящее время не требуется).

## Настройка

1. Подключите [jenkins shared library](https://github.com/alexanderbazhenoff/jenkins-shared-library) с именем
   `jenkins-shared-library-alx` (cм.
   [официальную документацию](https://www.jenkins.io/doc/book/pipeline/shared-libraries/#global-shared-libraries)).
2. Установите все [необходимые плагины](https://www.jenkins.io/doc/book/managing/plugins/)) на сервер Jenkins (см.
   ['Требования'](#требования)).
3. Установите значения всех констант pipeline'а (особенно репозитории) (см.
   ["константы pipeline"](#константы-pipeline)).
4. Прочтите [подробную инструкцию](https://github.com/alexanderbazhenoff/universal-wrapper-pipeline-settings) с
   описанием формата конфигурационных файлов, чтобы создать свой, или используйте уже готовые примеры (например,
   ['example-pipeline'](https://github.com/alexanderbazhenoff/universal-wrapper-pipeline-settings/blob/main/settings/example-pipeline.yaml).
   Для этого вам нужно создать "pipeline из SCM" (pipeline from SCM) с тем же именем, что и конфигурационный файл
   (за исключением префикса имени и расширения - см. 'PipelineNameRegexReplace' в
   ["Константы pipeline"](#константы-pipeline)), который настроен на получение исходников на этого репозиторий и код в
   файле [jenkins-universal-wrapper-pipeline.groovy](jenkins-universal-wrapper-pipeline.groovy).
5. Некоторые используемые в коде pipeline методы могут потребовать администраторов подтвердить их использование (см.
   ["In-process Script Approval"](https://www.jenkins.io/doc/book/managing/script-approval/) в официально документации).

## Константы pipeline

Вы можете задать некоторые настройки pipeline через константы, или переопределить (override) их значения с помощью
переменных окружения (environment variables) без изменения кода pipeline (см.
["Переопределение констант"](#переопределение-констант): например, если вы хотите переопределить репозиторий с
настройками pipeline, сменить ветку, или относительный путь (relative path) к yaml файлам внутри него. Переменные
окружения переопределяют значения констант:

- Константа `SettingsGitUrl`, или переменная окружения `JUWP_SETTINGS_GIT_URL`: ссылка на репозиторий
  ['universal-wrapper-pipeline-settings'](https://github.com/alexanderbazhenoff/universal-wrapper-pipeline-settings/tree/main)
  для загрузки настроек текущего pipeline.
- Константа `DefaultSettingsGitBranch`, или переменная окружения `JUWP_DEFAULT_SETTINGS_GIT_BRANCH`: ветка в репозитории
  ['universal-wrapper-pipeline-settings'](https://github.com/alexanderbazhenoff/universal-wrapper-pipeline-settings/tree/main).
- Константа `SettingsRelativePathPrefix`, или переменная окружения `JUWP_RELATIVE_PATH_PREFIX`: префикс, или
  относительный путь (relative path) к yaml файлам внутри репозитория
  ['universal-wrapper-pipeline-settings'](https://github.com/alexanderbazhenoff/universal-wrapper-pipeline-settings/tree/main),
  который будет автоматически подставлен при загрузке yaml (например: папка `settings`).
- Константа `PipelineNameRegexReplace` (с типом "список"), или переменная окружения`JUWP_PIPELINE_NAME_REGEX_REPLACE`
  (с разделенным запятыми списком регулярных выражений, например: `'value1, value2, value3'`): регулярные выражения
  для имени Jenkins pipeline'а, строка, которая будет отрезана из имени pipeline'а, прежде, чем станет именем
  загружаемых настроек pipeline'а.
- Константа `AnsibleInstallationName`: имя ansible установки в Jenkins Global Configuration Tool, или пустая строка для
  использования дефолтных значений из
  [jenkins shared library](https://github.com/alexanderbazhenoff/jenkins-shared-library) (см. документацию к
  ['Ansible Jenkins plugin'](https://plugins.jenkins.io/ansible/)). *Не используется и, вероятно, в скором времени
  будет удалена, так как последние изменения
  [jenkins shared library](https://github.com/alexanderbazhenoff/jenkins-shared-library)* запускают по умолчанию ansible
  playbook'и через вызов shell.*
- Константа `BuiltinPipelineParameters`: встроенные параметры pipeline, которые обязательны, но не присутствуют в
  настройках pipeline ('universal-wrapper-pipeline-settings'): заданные здесь параметры pipeline `UPDATE_PARAMETERS`,
  `SETTINGS_GIT_BRANCH`, `NODE_NAME`, `NODE_TAG`, `DRY_RUN` и `DEBUG_MODE` являются системными. Их изменение не
  рекомендуется.

## Переопределение констант

Вы можете переопределить [константы pipeline](#константы-pipeline) без изменения кода pipeline, используя
предустановленные переменные окружения. Установите их в настройках ноды (через выбор ноды в меню "Manage Jenkins
Nodes"), или лучше всего - в опции "Prepare an environment for the run" в настройках pipeline. Как описывает
официальная документация [Environment Injector'а](https://plugins.jenkins.io/envinject/), включите опцию "Prepare an
environment for the run" и в поле "Properties Content" выпадающего меню впишите ваши значения переменных окружения,
например:

```properties
JUWP_SETTINGS_GIT_URL=http://github.com/my_usrrname/my_universal-wrapper-pipeline-settings-repository
JUWP_DEFAULT_SETTINGS_GIT_BRANCH=my_branch
```

<!-- docs-ci-cut-begin -->
## Ссылки

- [Wiki](https://github.com/alexanderbazhenoff/universal-wrapper-pipeline-settings/wiki).
- [Universal wrapper pipeline settings](https://github.com/alexanderbazhenoff/universal-wrapper-pipeline-settings/tree/main)
  репозиторий с описанием формата конфигурационных файлов и примерами настроек.

<!-- docs-ci-cut-end -->
