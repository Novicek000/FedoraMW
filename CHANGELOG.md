# Historia zmian

## 0.2.2

- Automatyczne usuwanie pobranego ISO po pomyślnym zapisie, pełnej weryfikacji SHA-256 USB i zamknięciu połączenia z pendrivem.
- Po błędzie lub anulowaniu obraz pozostaje do ponownej próby.
- Nieudane usuwanie ISO jest zgłaszane osobno i nie oznacza błędnego zapisu USB.

## 0.2.1

- Po odrzuceniu SYNCHRONIZE CACHE odczytywana jest przyczyna SCSI. Potwierdzony brak obsługi polecenia pozwala kontynuować pełną weryfikację USB; nie powoduje już żądania ponownego nagrania.
- Sukces nadal wymaga pełnego odczytu i zgodności SHA-256. Aplikacja informuje, gdy nośnik nie obsługuje potwierdzenia opróżnienia bufora. Rzeczywiste i nieznane błędy pozostają błędami.
- Komunikaty rozróżniają przerwany zapis od błędu finalizacji/weryfikacji; diagnostyka zawiera nazwę polecenia i dostępny kod sense.
- Osobny etap finalizowania zapisu; obsługa krótkich odpowiedzi REQUEST SENSE.
- 31 testów jednostkowych przechodzi. Brak testu poprawki na fizycznym nośniku.

## 0.2.0

- Dwa pola wyboru: Edycja i Wydanie.
- Grupy Oficjalne edycje, Atomic Desktops, Spins i Labs zgodne z oryginalnym MediaWriterem.
