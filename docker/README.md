# Refactoring Java Code towards Language Evolution

### Requirements

   * Docker version >= 20.10.21

### Build and run

   * Clone this repository (`git clone git@github.com:PAMunb/rascal-Java8.git`)
   * Change to the JUnit5Migration/docker folder (`cd rascal-Java8/docker`)
   * Execute these commands in your terminal:

```shell
$ docker build -t rjtl -f Dockerfile.rj .

$ docker run --name rjtl -w /home/rascal-Java8/ -it -v [LOCATION_OF_DATASET_IN_YOUR_HOST]:/home/dataset -v [LOCATION_OF_OUTPUT_DIRECTORY_IN_YOUR_HOST]:/home/rascal-Java8/output rjtl python3 driver.py -i home/input.csv
```
