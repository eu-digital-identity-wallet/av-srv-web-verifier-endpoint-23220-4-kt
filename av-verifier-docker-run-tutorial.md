# Step by step how to run Age Verification verifier
## Description
Step by step guide how to run the Age Verification verifier locally while using docker.
## Requirements

- docker
- git

Make sure no other services are running at port 443, 8080, 9090!
## Run docker
Make sure docker is up2date and running!

## Run the backend of the verifier
*Note: this will run some extra services not needed but it is not important*

Run command:

`https://github.com/eu-digital-identity-wallet/av-srv-web-verifier-endpoint-23220-4-kt.git`

Run command:

`cd av-srv-web-verifier-endpoint-23220-4-kt`

Run commands:

`cd docker`

`docker compose up -d`

## Age Verification UI Adjustment
- ``git clone https://github.com/eu-digital-identity-wallet/av-web-verifier-ui.git``
- ``cd av-web-verifier-ui``

## Run Age Verification UI
*Warning: Do not run npm install locally!*

- Delete file package-lock.json
- Delete folder if present node-modules

Run command:

`echo "VITE_VERIFIER_BASE_URL=http://localhost:8080" > .env`

Run command:

`docker run -p 9090:80 --rm -it $(docker build -q .)`

## Open browser

[http://localhost:9090]([http://localhost:9090])