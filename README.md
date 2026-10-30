# Lepší moment

První nativní Android aplikace pro sestavení lepší skupinové fotografie z několika podobných snímků. Určeno především pro **Samsung Galaxy S24**; vyžaduje Android 10 nebo novější a procesor ARM64.

## Použití

1. Otevřete aplikaci a vyberte **2–6 fotek** stejné skupiny, ideálně ze série krátce po sobě. V galerii vyberete snímek klepnutím na fotku; zaškrtnutí vlevo nahoře je vidět pouze u vybraných fotek. Vpravo nahoře je o třetinu menší poloprůhledná ikona zvětšení. Zvětšená fotka nahradí pouze oblast mřížky; lze ji přiblížit dvěma prsty, vybrat checkboxem a stejnou ikonou se vrátit do mřížky. Mezi snímky přejdete tahem doleva/doprava nebo šipkami dole. Při přiblížení jedním prstem posouváte fotku; swipe přepíná při základním zvětšení. Mřížka včetně načtených náhledů zůstává v paměti i během zvětšeného náhledu.
2. Zvolte hlavní fotografii a klepněte na obličej, který chcete upravit.
3. Vyberte lepší výraz **stejného člověka** z jiného snímku. Nabídka používá polohu ve fotografii, nikoli rozpoznávání identity. Pokud se lidé přesunuli, otevřete „Vybrat z dalších obličejů“.
4. Přibližte hlavní fotku dvěma prsty a posouvejte jedním. „Rámečky“ skryje nebo zobrazí označení obličejů; výběr osoby funguje i bez rámečků. Pomocí „Před / Po“ zkontrolujte rozdíl při stejném měřítku a posunu; poloprůhledný popisek původní fotky je přímo přes spodní okraj snímku.
5. „Doladit“ zobrazí nahoře upravovaný obličej s okolím (obličej zabírá přibližně polovinu náhledu), dole posuvníky. Posun, velikost obličeje, natočení, velikost masky, měkkost okraje a sladění barev se projeví průběžně. Záporná velikost masky zúží její okraj (eroze), kladná jej rozšíří (dilatace); nemění měřítko obličeje. „Použít“ potvrdí celou úpravu jako jeden krok, „Zrušit“ nebo systémové Zpět vrátí předchozí stav, „Výchozí“ resetuje posuvníky bez opuštění obrazovky. Můžete upravit více osob.
6. „Uložit novou fotku“ vytvoří JPG ve složce **Pictures/Lepsi moment**. Originály zůstanou beze změny.

Tlačítko „Původní“ vrací výraz vybrané osoby. „Zpět“ vrací poslední změnu. Rozpracovaná úprava se průběžně ukládá; po novém spuštění zvolte „Pokračovat v poslední úpravě“. Obnovení vyžaduje, aby původní fotky byly stále dostupné.

## Instalace na telefon

Hotové testovací APK je v `artifacts/Lepsi-moment-0.3.0.apk`. Přeneste soubor do telefonu a otevřete jej. Má vyšší versionCode a stejný vývojový podpis jako zde sestavené verze 0.1.0 a 0.2.0, takže se instaluje jako aktualizace. Předchozí verzi není potřeba odinstalovat. Android může požádat o povolení instalace z aplikace, ve které APK otevíráte. Jde o lokálně podepsanou testovací sestavu, nikoli o aplikaci vydanou v Google Play.

## Co tato verze dělá

- Vlastní galerie se zvětšením fotky v oblasti mřížky a výběrem přímo v náhledu. Podporuje úplný i částečný přístup k fotografiím Androidu 14+. Přístup žádá až při otevření galerie, dostupné položky obnovuje po návratu do aplikace. „Další zdroj…“ zachovává systémový Photo Picker pro cloudové fotky nebo použití bez oprávnění ke galerii.
- Lokální detekce více obličejů pomocí přibaleného ML Kit Face Detection 16.1.7.
- Samostatná detekce obrysu každého obličeje ve výřezu. ML Kit při zapnutých konturách na celé skupinové fotografii poskytuje konturu jen dominantní tváře; proto se zpracovává každý výřez zvlášť.
- Zarovnání podle obou očí pomocí podobnostní transformace. Tvar úst a výraz ze zdrojového snímku zůstává zachovaný.
- Průnik masek obou tváří, nastavitelná eroze/dilatace vzdálenostní transformací, bilineární převzorkování, vyrovnání barvy u okraje a plynulé prolínání. Když obrys není dostupný, použije se oválná maska.
- Živé doladění dekóduje zdroje jednou a následně přepočítává výřezy. Z rychlých změn posuvníků drží jen poslední čekající nastavení. Náhled nemění potvrzenou úpravu ani historii; nové parametry masky se ukládají do rozpracovaného stavu. Starší uložené úpravy používají výchozí nulovou erozi/dilataci.
- Obrázky se zpracovávají mimo hlavní vlákno, po jednom. V paměti zůstávají malé náhledy; samotné prolínání používá jen výřezy kolem tváře.
- Otočení a barevný prostor při načtení řeší Android ImageDecoder.
- Ukládání přes MediaStore do nové položky, včetně odstranění neúplného souboru při chybě.

## Soukromí a omezení

Aplikace nemá oprávnění `INTERNET`, nepoužívá server ani účet. Detekční model je součástí APK. Vlastní galerie potřebuje povolení ke čtení fotek; na novějších Androidech lze povolit jen konkrétní snímky a později je změnit tlačítkem „Změnit dostupné fotky“. Volba „Další zdroj…“ otevře systémový výběr, který může nabídnout fotografie z uživatelova cloudového poskytovatele; samotná aplikace snímky neodesílá. Soukromý stav aplikace je vyloučen ze zálohování a přenosu dat.

Nejde o implementaci ani oficiální aplikaci Google Best Take. Jde o první funkční prototyp s geometrickým skládáním obličejů, nikoli generativní přestavbou hlavy. Nejlépe funguje při podobném osvětlení a natočení tváře. Silný profil, překážky před obličejem, výrazně jiné světlo, velmi malé tváře a velká změna úhlu mohou způsobit viditelné přechody. Příliš odlišné natočení aplikace odmítne. Návrhy podle polohy mohou obsahovat jiného člověka; správný zdroj volí uživatel.

Export je **JPG v sRGB / SDR**, kvalita 97, maximálně **4096 pixelů na delší straně**. Běžné 12MP snímky 4000 × 3000 px zůstanou v plném rozlišení. Větší snímky se zmenší. Nepřenáší se Ultra HDR, Motion Photo, RAW ani původní EXIF/GPS metadata. Aplikace není editor RAW.

## Sestavení

Potřebujete **JDK 17**, Android SDK 35 a Build Tools 35.0.0. Projekt používá Android Gradle Plugin 8.9.2 a Gradle 8.11.1, verze závislostí jsou pevně dané.

Otevřete adresář projektu v Android Studiu nebo nastavte `sdk.dir` v lokálním `local.properties` a spusťte:

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

APK vznikne v `app/build/outputs/apk/debug/app-debug.apk`. Pro jiné procesorové architektury upravte `ndk.abiFilters` v `app/build.gradle`.

Integrační testy s připojeným ARM64 zařízením nebo emulátorem:

```sh
./gradlew :app:connectedDebugAndroidTest
```

Při prvním sestavení se stahuje Gradle a závislosti; běh aplikace je offline. Lokální `.tooling/` obsahuje vývojové nástroje a není součástí zdrojového balíčku. Release podepisování a publikace do obchodu nejsou nastaveny.

V tomto pracovním adresáři lze použít `sh scripts/build-local.sh`; využije již připravené nástroje a zachová stejný lokální testovací podpis. Soukromý podpisový klíč není součástí zdrojového ZIP. Při sestavení na jiném počítači vznikne jiný testovací klíč; Android pak místo aktualizace může vyžadovat odinstalování předchozí testovací verze (tím se odstraní její rozpracovaný stav, nikoli exportované fotky).

## Ověření

Automatické testy ověřují geometrii, zachování obrazu při identické náhradě, nedotčené okolí masky, měkký přechod, korekci barev, oříznutý zdroj a chybějící oči. Testy na Androidu zahrnují lokální detekci více tváří, kontury každé tváře, výměnu jen vybrané osoby, export, vrácení změny a obnovení Activity.

Testovací skupina je záměrně syntetická koláž veřejně dostupného portrétu NASA. Ověřuje funkčnost zpracování, nikoli přirozenost výměny různých výrazů v reálné sérii. Uživatel potvrdil fungování verze 0.1.0 na svém telefonu; nová verze je ověřována samostatně. Výsledky původní verze jsou v `VALIDATION.md`, ověření verze 0.2.0 v `VALIDATION-0.2.md` a aktuální změny v `VALIDATION-0.3.md`.

## Zdroje

- [ML Kit Face Detection pro Android](https://developers.google.com/ml-kit/vision/face-detection/android)
- [Možnosti a omezení detekce obličejů](https://developers.google.com/ml-kit/vision/face-detection/face-detection-concepts)
- [Android Photo Picker](https://developer.android.com/training/data-storage/shared/photo-picker)
- [Android: částečný přístup k fotografiím ve vlastní galerii](https://developer.android.com/about/versions/14/changes/partial-photo-video-access)
- [Testovací portrét NASA / scikit-image](https://scikit-image.org/docs/stable/api/skimage.data.html#skimage.data.astronaut)

Zdrojový kód: `app/src/main/java/cz/honza/lepsimoment/`. Čisté matematické jádro je v podsložce `core/` a nemá závislost na Androidu.
