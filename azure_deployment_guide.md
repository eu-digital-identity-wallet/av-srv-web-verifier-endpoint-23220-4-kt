# Azure Deployment Guide - EUDI Services

## Prerequisites

- [Azure CLI](https://docs.microsoft.com/en-us/cli/azure/install-azure-cli) installed
- [Docker](https://docs.docker.com/get-docker/) with buildx support
- Valid Azure subscription with appropriate permissions
- Access to Azure Container Registry (ACR)

## Deployment Workflow

### Step 1: Azure Authentication

```bash
# Login to Azure
az login --tenant 922f1ea1-d97d-4877-9eab-aa5e201fe58e
```

Select the right subscription (Age Verification DEV)

```bash
# Verify current subscription
az account show --output table
```

### Step 2: Azure Container Registry Setup

```bash
# Login to your Azure Container Registry
az acr login --name ageverificationdev

# Verify registry access
az acr repository list --name ageverificationdev --output table
```

### Step 3: Build and publish docker image

```bash
docker build -t ageverificationdev.azurecr.io/your-service-name:latest .

# Tag with version number (optional)
docker tag ageverificationdev.azurecr.io/your-service-name:latest \
  ageverificationdev.azurecr.io/your-service-name:v1.0.0

# Push to Azure Container Registry
docker push ageverificationdev.azurecr.io/your-service-name:latest
docker push ageverificationdev.azurecr.io/your-service-name:v1.0.0
```

### Step 4: Azure App Service Deployment

#### Via Azure Portal:

1. **Navigate to App Service**

    - Go to [Azure Portal](https://portal.azure.com)
    - Select your App Service resource
    - Go to **Deployment** → **Deployment center**

2. **Configure Container Settings**

    - **Image Source**: `Azure Container Registry`
    - **Registry**: Select your ACR (e.g., `ageverificationdev`)
    - **Image**: Select your repository (e.g., `av-verifier-ui`)
    - **Tag**: Select the desired tag (e.g., `latest` or `v1.2.3`)

3. **Save and Deploy**
    - Click **Save**
    - App Service will automatically pull and deploy the new container
