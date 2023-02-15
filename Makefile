test:
	sbt test

build_recs:
	sbt build_recs

run_server:
	sbt run_server

# Run this if Spark is causing trouble - it's a little slower but it will work :)
build_recs_docker:
	docker build -t spark_recs . && docker run -v $(pwd):/rec_project -it spark_recs
