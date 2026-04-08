Start-Process java -ArgumentList "-Dfile.encoding=GBK", "-jar", "unilink-proxy\target\unilink-proxy-1.2.0.jar"
Start-Sleep -Seconds 8
Start-Process java -ArgumentList "-Dfile.encoding=GBK", "-jar", "unilink-worker\target\unilink-worker-1.2.0.jar"
Start-Sleep -Seconds 8
Start-Process java -ArgumentList "-Dfile.encoding=GBK", "-jar", "unilink-access\target\unilink-access-1.2.0.jar"