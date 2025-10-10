# Step by step how to run Age Verification verifier
## Description
Step by step guide how to run the Age Verification verifier locally using docker. *(Tested on OSX and Terminal app)*

## Trusted issuers
The verifier is per default configured to trust issuers listed in the file [src/main/resources/av-etsi-trusted-list.xml](src/main/resources/av-etsi-trusted-list.xml) .

The configured issuer is in this case the one from Brussels.


## Requirements

- docker
- git

*Make sure no other services are running at port 443, 8080, 9090!*

## Run docker
Make sure docker is updated and running!

## Run the backend of the verifier
*Note: this will run some extra services not needed but it is not important*

Run commands:

`git clone https://github.com/eu-digital-identity-wallet/av-srv-web-verifier-endpoint-23220-4-kt.git`

`cd av-srv-web-verifier-endpoint-23220-4-kt`

`cd docker`

`docker compose up -d`

## Age Verification UI Adjustment
Open another terminal

Run commands:

``git clone https://github.com/eu-digital-identity-wallet/av-web-verifier-ui.git``

``cd av-web-verifier-ui``

## Run Age Verification UI
*Warning: Do not run npm install locally!*

- Delete file package-lock.json `rm package-lock.json`
- Delete folder if present node-modules `rm -rf node-modules`

Run commands:

`echo "VITE_VERIFIER_BASE_URL=http://localhost:8080" > .env`

`docker run -p 9090:80 --rm -it $(docker build -q .)`

## Open browser

[http://localhost:9090]([http://localhost:9090])