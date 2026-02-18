# Contribuir a capacitor-sumup

¡Gracias por tu interés en contribuir! 🙌

## Cómo contribuir

1. Haz un **fork** del repositorio
2. Crea una rama para tu feature o fix: `git checkout -b mi-cambio`
3. Haz tus cambios y commitea: `git commit -m "Agrega tal cosa"`
4. Sube tu rama: `git push origin mi-cambio`
5. Abre un **Pull Request** en GitHub

## Desarrollo local

```bash
# Clonar
git clone https://github.com/chano195/capacitor-sumup.git
cd capacitor-sumup

# Instalar dependencias
npm install

# Compilar TypeScript
npm run build
```

## Estructura del proyecto

```
├── android/          # Código nativo Java (SumUpPlugin.java)
├── src/              # Código TypeScript (definiciones, web fallback)
├── dist/             # Archivos compilados (generado por npm run build)
├── package.json
├── tsconfig.json
└── README.md
```

## Reglas

- Escribe código limpio y legible
- Respeta la estructura existente del proyecto
- Documenta los cambios públicos en el README
- Los PRs deben compilar sin errores (`npm run build`)

## Reportar bugs

Abre un [issue en GitHub](https://github.com/chano195/capacitor-sumup/issues) con:

- Descripción del problema
- Pasos para reproducir
- Versión del plugin, Capacitor y Android
- Logs relevantes

## Atribución y créditos

- Al contribuir, aceptas que tu código se publique bajo la licencia **MIT**.
- Si usas este plugin en tu proyecto, te pedimos que des crédito al proyecto original
  (una mención en tu README, en la sección "Acerca de" de tu app, o similar).
- La nota de copyright en el archivo LICENSE **debe mantenerse** — esto es un requisito legal de la licencia MIT.

Cada línea de código compartida es un ladrillo en un mundo que todavía no existe.
