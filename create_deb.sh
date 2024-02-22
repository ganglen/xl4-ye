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

if [ -d "./usr/share/excelfore-yang-explorer/anc/target/site" ]; then
    echo "Directory ./usr/share/excelfore-yang-explorer/anc/target/site already exists."
else
    mkdir -p ./usr/share/excelfore-yang-explorer/anc/target/site
    echo "Directory ./usr/share/excelfore-yang-explorer/anc/target/site created."
    cp -rf ./anc/target/site/apidocs ./usr/share/excelfore-yang-explorer/anc/target/site/
fi

cd ../ &&
dpkg-deb --build excelfore-yang-explorer &&
cd excelfore-yang-explorer &&
mv ../excelfore-yang-explorer.deb . &&
echo "SUCCESS: excelfore-yang-explorer.deb created" ||
echo "ERROR: Failed to create excelfore-yang-explorer.deb"

# Cleanup
rm -rf ./usr

exit 0
