@echo off
cd /d "%~dp0"

where node >nul 2>nul
if errorlevel 1 (
  echo No se encontro Node.js instalado. Instalalo desde https://nodejs.org y volve a intentar.
  pause
  exit /b 1
)

if not exist "node_modules" (
  echo Primera vez: instalando dependencias con npm install...
  call npm install
  if errorlevel 1 (
    echo Fallo npm install. Revisa el error de arriba.
    pause
    exit /b 1
  )
)

if not exist ".env" (
  echo No existe .env todavia - se crea una copia de .env.example para que completes tu API key.
  copy ".env.example" ".env" >nul
  notepad ".env"
  echo Guarda el archivo con tu API key y cerra el Bloc de notas para continuar.
  pause
)

call npm start
