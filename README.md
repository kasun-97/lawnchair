# Lawnchair 16 — personal fork

> [!NOTE]
> This is an **unofficial personal fork** of [Lawnchair](https://github.com/LawnchairLauncher/lawnchair). It is not affiliated with or supported by the Lawnchair team. Please don't report issues from this fork to upstream.
>
> There are no prebuilt APKs; build it yourself (see [Building](#building)). For official releases, use the [upstream project](https://github.com/LawnchairLauncher/lawnchair#download).

Lawnchair is a free, open-source home app for Android. Taking Launcher3—Android’s default home app—as a starting point, it ports Pixel Launcher features and introduces rich customization options.

This fork tracks upstream's `16-dev` branch (Lawnchair 16, based on Launcher3 from Android 16) and adds the features below on the `profiles` branch.

## What this fork adds

### App drawer tabs
- Custom tabs in the app drawer, shown as swipeable pages with a sliding tab pill.
- Settings for each tab: title, app picker, and an option to hide its apps from the "All" tab.
- Long-press a tab to open its settings.

### Built-in feed
- A feed page inside the launcher, selectable as a feed provider next to the Google feed.
- RSS headlines with a title, a headline limit and per-feed switches.
- App sections, and widgets from other apps.
- Hold a widget to resize or remove it; hold and drag to reorder widgets and sections; hold a title to rename it.

### At a Glance cards
- Next alarm (within 12 hours, with a countdown) and Do Not Disturb.
- Phone calendar: the event in progress and the next one, with a selectable look-ahead window.
- Weather from [Open-Meteo](https://open-meteo.com/) (no Google account needed), with its own icons, a "rain soon" card and a tap that opens [windy.com](https://www.windy.com/).

### Smaller changes
- A search field on the app picker pages (drawer tabs, feed sections, hidden apps, gesture app pickers).

## Building

Requirements: JDK 21 and the Android SDK.

```sh
git clone --recurse-submodules -b profiles https://github.com/kasun-97/lawnchair.git
cd lawnchair
export ANDROID_HOME="$HOME/Android/Sdk"
./gradlew assembleLawnWithQuickstepGithubDebug
```

The APK is written to `build/outputs/apk/lawnWithQuickstepGithub/debug/`. It installs as `app.lawnchair.debug`, alongside any official Lawnchair install.

For full environment setup, see the [Lawnchair developer docs](https://docs.lawnchair.app/developers/introduction/get-started).

## Credits

All credit for Lawnchair itself goes to the [Lawnchair team and contributors](https://github.com/LawnchairLauncher/lawnchair/graphs/contributors). If you like Lawnchair, consider [supporting them on Open Collective](https://opencollective.com/lawnchair).

## License

Apache License 2.0, as upstream. See [LICENSE.txt](LICENSE.txt).
