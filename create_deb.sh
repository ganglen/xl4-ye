#!/bin/bash
set -e

if [ -d "./usr/share/man/man1" ]; then
    echo "Directory ./usr/share/man/man1 already exists."
else
    mkdir -p ./usr/share/man/man1
    echo "Directory ./usr/share/man/man1 created."
fi

# Execute the mvn command and handle errors
if mvn package javadoc:javadoc; then
    echo "Maven build successful."
else
    echo "Maven build failed."
    exit 1
fi

if [ -d "./usr/share/excelfore-yang-explorer/explorer/target" ]; then
    echo "Directory ./usr/share/excelfore-yang-explorer/explorer/target already exists."
else
    mkdir -p ./usr/share/excelfore-yang-explorer/explorer/target
    echo "Directory ./usr/share/excelfore-yang-explorer/explorer/target created."
    cp -r ./explorer/target/explorer-1.0-SNAPSHOT.war ./usr/share/excelfore-yang-explorer/explorer/target/
fi

if [ -d "./usr/share/excelfore-yang-explorer/anc/target/reports" ]; then
    echo "Directory ./usr/share/excelfore-yang-explorer/anc/target/reports already exists."
else
    mkdir -p ./usr/share/excelfore-yang-explorer/anc/target/reports
    echo "Directory ./usr/share/excelfore-yang-explorer/anc/target/reports created."
    cp -rf ./anc/target/reports/apidocs ./usr/share/excelfore-yang-explorer/anc/target/reports/
fi

cd ../ &&
dpkg-deb --build excelfore-yang-explorer xl4yangexplorer_1.0.2_amd64.deb &&
cd excelfore-yang-explorer &&
mv ../xl4yangexplorer_1.0.2_amd64.deb . &&
echo "SUCCESS: xl4yangexplorer_1.0.2_amd64.deb created" ||
echo "ERROR: Failed to create xl4yangexplorer_1.0.2_amd64.deb"

# Cleanup
rm -rf ./usr

exit 0
