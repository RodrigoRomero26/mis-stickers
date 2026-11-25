# Mis Stickers — creador de stickers para WhatsApp (uso personal)

## Qué hace
- **Packs estáticos**: desde cualquier foto o imagen. Ajustar o recortar al cuadrado, texto con borde y emoji.
- **Packs animados**: desde un video o GIF. Elegís desde dónde empieza y cuánto dura (hasta 10 s). La app genera un WebP animado de 512×512 y baja fps y calidad sola hasta que entre en los 500 KB que pide WhatsApp.
- **Agregar a WhatsApp** con un toque (también funciona con WhatsApp Business). Si después agregás o borrás stickers, volvé a tocar el botón para actualizar el pack.
- **Preparar sticker con sonido**: recorta un video a 10 s como máximo y, si querés, le cambia el audio por un .m4a. Lo guarda en Películas/MisStickers para cargarlo en "Crear sticker" de WhatsApp con el audio original.

> Por qué el sonido va por separado: WhatsApp lanzó los stickers con sonido en noviembre de 2026, pero solo se crean desde su propio editor. La API para apps de terceros todavía no existe (ver WhatsApp/stickers#1163). Cuando la publiquen, se puede sumar el audio directo al pack.

Reglas de WhatsApp que la app ya respeta: de 3 a 30 stickers por pack, un pack es todo estático o todo animado, los estáticos pesan hasta 100 KB, los animados hasta 500 KB y 10 s, y el ícono del pack es un PNG de 96×96.

Requiere Android 10 o superior.

## Cómo sacar el APK

### Opción A: Android Studio
1. Abrí la carpeta `StickerMaker` con **File → Open**.
2. Esperá a que termine el *Gradle sync*.
3. Andá a **Build → Build App Bundle(s) / APK(s) → Build APK(s)**.
4. El APK queda en `app/build/outputs/apk/debug/app-debug.apk`. Pasalo al celular e instalalo (hay que permitir "instalar apps desconocidas").

### Opción B: GitHub, sin instalar nada
1. Creá un repo privado y subí esta carpeta completa, incluida `.github/`:
   ```
   git init && git add . && git commit -m "Mis Stickers"
   git branch -M main
   git remote add origin https://github.com/TU_USUARIO/mis-stickers.git
   git push -u origin main
   ```
2. En la pestaña **Actions** se ejecuta "Compilar APK" (tarda 3-5 minutos).
3. Cuando termina, entrá al run, bajá el artefacto **MisStickers-apk** (es un .zip) y adentro está el APK.

## Estructura
- `WebpAnim.java`: arma el WebP animado (Android no trae un codificador de WebP animado).
- `AnimatedBuilder.java`: saca los cuadros y ajusta el peso.
- `StickerContentProvider.java`: el contrato que usa WhatsApp para leer los packs.
- `VideoTrimmer.java`: recorta el video sin recodificar, para el sticker con sonido.
