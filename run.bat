@echo off
set WEATHER_API_KEY=SxJ93XfmdHOVyp8CO
set TARGET=%~dp0target\cli-toolbox-0.1.0.jar
java -jar "%TARGET%" %*
