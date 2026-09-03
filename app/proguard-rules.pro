# Regole app-specifiche. Le regole dei moduli engine viaggiano con i moduli.

# I nomi restano quelli veri. Questa app si prova su un telefono, e quando cade lascia la
# traccia nel quaderno degli errori (Impostazioni > Diagnostica): una traccia con `a.b.c` al
# posto dei nomi non si legge, e senza un computer attaccato non c'e' modo di rimapparla.
# Il taglio del codice inutilizzato resta: e' quello che pesa davvero sull'APK.
-dontobfuscate

# Le righe di sorgente nelle tracce: senza, un crash dice la classe ma non la riga.
-keepattributes SourceFile,LineNumberTable
