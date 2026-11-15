# FedoraMW — Android / Material 3 Expressive

Natywna, niezależna aplikacja Android inspirowana [Fedora Media Writer](https://github.com/FedoraQt/MediaWriter). Pobiera oficjalny obraz ISO Fedory i zapisuje go sektorowo na pendrive podłączony przez USB OTG, bez roota. Interfejs po polsku, w Kotlinie i Jetpack Compose, z motywem Material 3 Expressive, jasnym i ciemnym wyglądem, ekspresyjnym wskaźnikiem ładowania oraz falującym paskiem postępu.

To osobna implementacja Androida, a nie kompilacja desktopowego Qt ani oficjalny produkt Fedora Project. Kod C++/QML upstream nie jest włączony do APK. Nazwy, kolejność i grupy edycji pochodzą z metadanych upstream.

## Instalacja i użycie

1. Pobierz [FedoraMW-0.2.1-debug.apk z Releases](https://github.com/Novicek000/FedoraMW/releases/download/v0.2.1/FedoraMW-0.2.1-debug.apk) i zainstaluj na telefonie z Androidem 8 lub nowszym. To APK testowe, podpisane kluczem debug.
2. Wybierz edycję z pogrupowanej listy (Oficjalne edycje, Atomic Desktops, Spins, Labs), a następnie wydanie z osobnego pola. Układ, nazwy i kolejność edycji odpowiadają metadanym oryginalnego Fedora Media Writer. Ten uproszczony wybór używa obrazów x86_64 dla komputerów Intel / AMD, niezależnie od procesora telefonu.
3. Podłącz pendrive przez USB-C lub adapter OTG. Dotknij jego nazwy i zaakceptuj systemową zgodę USB.
4. Naciśnij „Pobierz i nagraj Fedorę”. Potwierdź wymazanie wskazanego nośnika.
5. Poczekaj na „Pendrive jest gotowy!”. Odłącz go dopiero po zakończeniu. Podczas działania możesz wygasić ekran; postęp pozostaje w powiadomieniu.

**Zapis usuwa poprzednie dane i tablicę partycji wybranego pendrive’a.** Przerwanie zapisu lub odłączenie kabla wymaga ponownego nagrania. Aplikacja nigdy automatycznie nie wznawia zapisu po restarcie procesu ani ponownym podłączeniu nośnika.

## Co działa

- Aktualny katalog z `https://fedoraproject.org/releases.json`; wyłącznie stabilne wydania ISO. Selektor pokazuje edycje x86_64 w grupach Oficjalne edycje, Atomic Desktops, Spins i Labs; osobne pole wydania zawiera tylko wersje wybranej edycji, od najnowszej.
- Pobieranie HTTPS z obsługą przekierowań do luster, przy odrzuceniu przekierowania do HTTP.
- Sprawdzenie rozmiaru i SHA-256 pobranego pliku względem katalogu, ponowne sprawdzenie pliku przed pierwszym zapisem.
- Prywatna kopia ISO w pamięci aplikacji; użycie poprawnej kopii przy kolejnym nagraniu. Nowe pobranie usuwa pozostałe zapisane ISO, aby nie kumulować dużych plików. Można je też usunąć w oknie informacji.
- USB Host: wykrywanie BOT/SCSI, zgoda systemowa, odczyt pojemności, kontrola rozmiaru przed pobraniem i przed zapisem.
- Surowy zapis od LBA 0, sektory 512/1024/2048/4096 B, dopełnienie ostatniego sektora zerami, SYNCHRONIZE CACHE(10). Jeśli REQUEST SENSE potwierdzi brak obsługi tego polecenia (bieżący ILLEGAL REQUEST, ASC/ASCQ 20/00 lub 24/00), aplikacja nadal wykonuje pełną weryfikację USB i informuje o braku potwierdzenia opróżnienia bufora. Pozostałe błędy nadal przerywają operację.
- Pełny odczyt zapisanych bajtów i porównanie SHA-256. Sukces jest zgłaszany dopiero po weryfikacji.
- Foreground service, powiadomienie z anulowaniem, blokada częściowa CPU, wykrywanie odłączenia USB i błędów transferu.
- Bez dostępu do całej pamięci telefonu i bez uprawnień administratora.

## Wymagania i ograniczenia wersji 0.2.1

Telefon musi obsługiwać USB Host / OTG i zasilać pendrive. Wspierane są USB Mass Storage, podklasa SCSI transparent (6), transport Bulk-Only (0x50), pojedynczy LUN. UAS, czytniki wielonośnikowe i urządzenia wymagające READ CAPACITY(16) nie są obsługiwane. Nie ma przywracania pendrive’a do FAT/exFAT, importu lokalnego ISO, zapisu skompresowanych RAW ani wznawiania częściowego pobrania. Uproszczony selektor nie oferuje obrazów ARM64.

Suma SHA-256 jest pobierana z oficjalnego katalogu przez HTTPS; aplikacja nie sprawdza podpisu OpenPGP pliku CHECKSUM. Potrzebne jest miejsce na całe ISO i 128 MiB zapasu. Android i producent telefonu mogą ograniczać czas foreground service; limit Androida jest obsługiwany jako przerwanie, nie jako sukces.

**Weryfikacja na fizycznym telefonie i pendrivie pozostaje do wykonania.** Testy z dyskiem pamięciowym i emulator ekranu nie potwierdzają zgodności wszystkich kontrolerów USB.

## Budowanie

Wymagane: JDK 17 lub 21, Android SDK Platform 36. Ustaw `sdk.dir` w lokalnym `local.properties` albo `ANDROID_HOME`. Wrapper pobiera Gradle 8.13.

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk`.

Material 3 `1.5.0-alpha01` udostępnia użyte API Expressive; wersja jest przypięta i wymaga eksperymentalnego opt-in. Zależności: Compose BOM 2025.09.00, Kotlin 2.1.20, AGP 8.10.1, OkHttp 4.12.0. Wydanie do sklepu wymaga własnego podpisu release.

## Struktura

- `MainActivity.kt` — ekran Compose, wybór obrazu i USB, systemowa zgoda i potwierdzenie wymazania.
- `EditionCatalog.kt` — grupy i nazwy edycji zgodne z oryginałem, przypisanie wydań i aliasów historycznych.
- `FedoraRepository.kt` — katalog, HTTPS, pobieranie, rozmiary i sumy.
- `WriterService.kt` — cykl pracy, foreground service, postęp, anulowanie, odłączenie.
- `UsbDisk.kt` — Android USB Host i polecenia SCSI.
- `BotProtocol.kt` — ramki CBW/CSW i kontrola odpowiedzi BOT.
- `CacheSync.kt` — rozpoznanie przyczyny odrzucenia synchronizacji i bezpieczne przejście do weryfikacji dla nieobsługiwanego polecenia.
- `ImageWriter.kt` — niezależny od Androida zapis i pełna weryfikacja.
- `WriterTest.kt` — testy protokołu i zapisu na dysku w pamięci.

## Pochodzenie i źródła

Punkt odniesienia: FedoraQt/MediaWriter, commit `9863ea389f6f1aa88b52c382226c48140e6bd67e`. Natywny kod Androida napisano od nowa; klasyfikację edycji opracowano na podstawie `src/app/data/assets/metadata.json` upstream. Projekt udostępniony na GPL-2.0-or-later; tekst licencji w `LICENSE`. Biblioteki AndroidX i OkHttp zachowują własne licencje. Nazwy Fedora i Android należą do odpowiednich właścicieli.

Dokumentacja techniczna:
- https://developer.android.com/develop/connectivity/usb/host
- https://developer.android.com/jetpack/androidx/releases/compose-material3
- https://www.usb.org/sites/default/files/usbmassbulk_10.pdf
- https://t10.org/ftp/t10/document.05/05-344r0.pdf
- https://fedoraproject.org/releases.json
