# Walidacja FedoraMW 0.2.2 — 2026-11-16

- `./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug`: BUILD SUCCESSFUL.
- Testy JUnit: 31, błędy: 0, niepowodzenia: 0.
- Lint: 0 błędów, 10 ostrzeżeń. Ostrzeżenia dotyczą dostępności nowszych zależności i alternatywnego API alokacji pamięci.
- Zweryfikowany manifest APK: nazwa FedoraMW, pakiet pl.fedoramw, wersja 0.2.2, minSdk 26, targetSdk 36.
- Katalog sprawdzony 2026-11-15, dostępny przez HTTPS: 147 stabilnych rekordów ISO x86_64/aarch64; zawiera Workstation.
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

## Synchronizacja USB w wersji 0.2.1

Zgłoszenie użytkownika: pendrive uruchamia Fedorę pomimo komunikatu „SCSI (status 1). Pendrive wymaga ponownego nagrania”. Wersja 0.2.0 przerywała weryfikację przy każdym odrzuceniu SYNCHRONIZE CACHE(10). To możliwe wyjaśnienie zgłoszenia; bez danych sense z konkretnego nośnika przyczyna sprzętowa nie jest potwierdzona.

Wersja 0.2.1 odczytuje REQUEST SENSE po statusie 1 synchronizacji. Wyłącznie bieżący ILLEGAL REQUEST (key 05, ASC/ASCQ 20/00 albo 24/00) oznacza brak obsługi polecenia. Wtedy następuje pełny odczyt i porównanie SHA-256; sukces zawiera informację, że nośnik nie potwierdził opróżnienia bufora. Błędy nośnika, sprzętu, odroczone, fazy BOT, nieznane i błędna diagnostyka pozostają błędami. Nie ukrywa się niezgodności odczytu.

Dodano etap „Finalizowanie zapisu”. Po zakończeniu przesyłania danych błąd finalizacji lub przerwanie weryfikacji nie powodują już automatycznego twierdzenia, że zapis jest niepełny. Wiadomość SCSI zawiera nazwę polecenia oraz dostępne key/ASC/ASCQ.

Nowe testy: 11 scenariuszy protokołu synchronizacji i REQUEST SENSE (format fixed/descriptor, krótka odpowiedź, kontrola residue, błędy medium/hardware/deferred/BOT i nieprawidłowa diagnostyka) oraz 4 scenariusze przepływu zapisu (pełna weryfikacja mimo braku synchronizacji, wykrywanie uszkodzenia, propagacja realnego błędu i anulowanie po synchronizacji). Wszystkie 31 testów przechodzi. Kompilacja i Lint bez błędów. Poprawka nie była testowana na fizycznym pendrivie użytkownika.

Źródła klasyfikacji: https://www.t10.org/lists/asc-num.htm oraz https://www.t10.org/ftp/t10/document.05/05-344r0.pdf.

## Usuwanie pobranego ISO w wersji 0.2.2

Po pomyślnym powrocie z ImageWriter.flash (pełny odczyt i zgodność SHA-256) i zamknięciu UsbDisk usuwany jest konkretny pobrany plik ISO. Dotyczy to także sukcesu po rozpoznanym braku obsługi synchronizacji bufora. Błąd lub anulowanie zapisu/weryfikacji nie docierają do usuwania, więc obraz pozostaje do ponownej próby.

Usuwanie pliku ma oddzielny wynik: jeśli nie uda się zwolnić miejsca, ekran nadal informuje o poprawnym USB i wskazuje możliwość ręcznego usunięcia ISO. Informacja w aplikacji i README odzwierciedla nowe zachowanie.

Sprawdzenie: assembleDebug, 31 istniejących testów jednostkowych, lintDebug — wszystkie przechodzą, Lint bez błędów. Przejrzano umiejscowienie usuwania po pełnej weryfikacji i zamknięciu urządzenia, poza ścieżkami błędu/anulowania. Nie dodawano testów samej operacji File.delete; nie wykonano próby na telefonie.
