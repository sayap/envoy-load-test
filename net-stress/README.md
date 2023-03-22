# net-stress

## Build

Install openjdk, and then run:
```
$ ./mvnw clean package -P http-get,grpc-get
```


## Example

```
## http, 4000 rps, 1024 threads, warm up for 10 seconds, stress test for 30 seconds
$ java -Dlog4j.configurationFile=log4j2.xml -jar target/http-get-jar-with-dependencies.jar -r 4000 -t 1024 -u http://192.168.144.14:60000/local -t 1024 -w 10 -s 30

## grpc, 4000 rps, 1024 threads, 4 connections, warm up for 10 seconds, stress test for 30 seconds
$ java -Dlog4j.configurationFile=log4j2.xml -jar target/grpc-get-jar-with-dependencies.jar -r 4000 -t 1024 -e 192.168.144.14:60000 -c 4 -w 10 -s 30
```
