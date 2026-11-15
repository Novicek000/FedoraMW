# Walidacja FedoraMW 0.2.0 — 2026-11-15

- `./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug`: BUILD SUCCESSFUL.
- Testy JUnit: 16, błędy: 0, niepowodzenia: 0.
- Lint: 0 błędów, 10 ostrzeżeń. Ostrzeżenia dotyczą dostępności nowszych zależności i alternatywnego API alokacji pamięci.
- Zweryfikowany manifest APK: nazwa FedoraMW, pakiet pl.fedoramw, wersja 0.2.0, minSdk 26, targetSdk 36.
- Bieżący oficjalny katalog dostępny przez HTTPS: 147 stabilnych rekordów ISO x86_64/aarch64; zawiera Workstation.
- Nie pobierano wielogigabajtowego ISO podczas testu i nie zapisywano żadnego fizycznego nośnika.
- Próby startu lokalnego emulatora Android 36.1 kończą się SIGSEGV procesu emulatora (RenderThread), także przy alternatywnych ustawieniach grafiki. Nie uzyskano weryfikacji ekranu w działającym Androidzie. To ograniczenie walidacji, nie potwierdzenie poprawnego uruchomienia aplikacji.

Testy obejmują: ramki CBW i kierunek transferu, payload zapisu, niewłaściwy tag CSW, pozostałość transferu, błąd SCSI, zapis wielu fragmentów, dopełnianie ostatniego sektora, sektory 4096 B, wykrywanie uszkodzonego odczytu, odrzucenie złej sumy i za małego nośnika przed zapisem oraz anulowanie przed zapisem.

## Próba odbiorcza na telefonie

1. Użyć pendrive’a z danymi, które można skasować, i telefonu USB OTG.
2. Sprawdzić brak dostępu po odmowie zgody USB i poprawny odczyt pojemności po zgodzie.
3. Pobrać Workstation x86_64 i potwierdzić wymazanie wybranego pendrive’a.
4. Poczekać na zakończenie pobierania, sprawdzania ISO, zapisu i odczytu weryfikacyjnego.
5. Sprawdzić start pendrive’a na docelowym komputerze i opcję testu nośnika Fedory.
6. Oddzielnie sprawdzić anulowanie i odłączenie — aplikacja powinna zgłosić przerwanie, bez automatycznego wznowienia po ponownym podłączeniu.

## Zmiana selektora w wersji 0.2.0

Dwa pola: Edycja oraz Wydanie. Pole Edycja ma grupy zgodne z oryginalnym MediaWriterem: Oficjalne edycje, Atomic Desktops, Spins, Labs; nazwy i kolejność z metadata.json upstream. Pole Wydanie ogranicza wersje do wybranej edycji i sortuje je malejąco. Obrazy selektora: x86_64 (Intel/AMD). Dodano pięć testów obejmujących grupy i kolejność, historyczne nazwy Atomic bez mieszania ze spinami, wersje dla wybranej edycji i architektury, nazwy Labs i brak podwójnych wpisów IoT. Wszystkie przechodzą. Brak nowego testu na fizycznym telefonie/USB.
