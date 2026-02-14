# Fix Docker Resource Limits for Ollama

## The Error You're Seeing

```
Error: Memory limit should be smaller than already set memoryswap limit
```

This happens because Docker's swap limit is set lower than the new memory limit you're trying to set.

## Solution 1: Update Both Memory and Swap (Recommended)

```bash
# Update both memory and memory-swap together
docker update ollama --cpus="6" --memory="8g" --memory-swap="10g"
```

**Explanation:**
- `--memory="8g"` - RAM limit
- `--memory-swap="10g"` - Total memory (RAM + Swap)
- Swap must be >= Memory

## Solution 2: Use Docker Desktop GUI (Easiest)

### For Windows Docker Desktop:

1. **Right-click Docker icon** in system tray
2. Click **Settings**
3. Go to **Resources** → **Advanced**
4. Adjust sliders:
   - **CPUs**: 6-8
   - **Memory**: 8 GB
   - **Swap**: 2 GB
5. Click **Apply & Restart**

This will restart Docker and apply settings to all containers.

## Solution 3: Recreate Container with Proper Limits

If update doesn't work, recreate the container:

```bash
# Stop and remove current container
docker stop ollama
docker rm ollama

# Create new container with proper resource limits
docker run -d \
  --name ollama \
  --cpus="6" \
  --memory="8g" \
  --memory-swap="10g" \
  -v ollama:/root/.ollama \
  -p 11434:11434 \
  ollama/ollama

# Verify it's running
docker ps | grep ollama

# Your models are preserved in the volume, so no need to re-download
docker exec -it ollama ollama list
```

## Solution 4: With GPU Support (Best Performance)

If you have an NVIDIA GPU:

```bash
# Stop and remove current container
docker stop ollama
docker rm ollama

# Create with GPU support and resource limits
docker run -d \
  --name ollama \
  --gpus=all \
  --cpus="6" \
  --memory="8g" \
  --memory-swap="10g" \
  -v ollama:/root/.ollama \
  -p 11434:11434 \
  ollama/ollama

# Test GPU is working
docker exec -it ollama nvidia-smi
```

## Verify Resource Allocation

After updating, check the resources:

```bash
# Check current resource limits
docker inspect ollama | grep -A 10 "Memory"

# Or use stats to see real-time usage
docker stats ollama
```

You should see something like:
```
CONTAINER ID   NAME     CPU %     MEM USAGE / LIMIT   
fad106daa68c   ollama   150.00%   2.5GiB / 8GiB
```

## Quick Test After Update

```bash
# Test with the fastest model
docker exec -it ollama ollama pull llama3.2:1b

# Benchmark speed
time docker exec -it ollama ollama run llama3.2:1b "Explain AI in one sentence"
```

**Expected time**: 15-30 seconds (down from 5 minutes)

## Troubleshooting

### If Docker Desktop is not responding:

1. Restart Docker Desktop completely
2. Wait for it to fully start
3. Try the update command again

### If you get "container is running" error:

```bash
# Stop container first
docker stop ollama

# Then update
docker update ollama --cpus="6" --memory="8g" --memory-swap="10g"

# Start it again
docker start ollama
```

### Check current limits:

```bash
# See what's currently set
docker inspect ollama --format='{{.HostConfig.Memory}}'
docker inspect ollama --format='{{.HostConfig.MemorySwap}}'
docker inspect ollama --format='{{.HostConfig.NanoCpus}}'
```

## Recommended Settings by Use Case

### Development (Balanced):
```bash
--cpus="4" --memory="6g" --memory-swap="8g"
```

### Production (Performance):
```bash
--cpus="8" --memory="12g" --memory-swap="16g"
```

### Minimal (Testing):
```bash
--cpus="2" --memory="4g" --memory-swap="6g"
```

## Alternative: No Resource Limits

If you want Ollama to use all available resources:

```bash
docker stop ollama
docker rm ollama

# Run without limits (use with caution)
docker run -d \
  --name ollama \
  -v ollama:/root/.ollama \
  -p 11434:11434 \
  ollama/ollama
```

## What to Do Now

**Choose one of these options:**

### Option A: Quick Fix (Docker Desktop GUI)
1. Open Docker Desktop Settings
2. Increase Resources
3. Apply & Restart
4. Test: `docker exec -it ollama ollama run llama3.2:1b "hello"`

### Option B: Command Line (Recreate Container)
```bash
docker stop ollama
docker rm ollama
docker run -d --name ollama --cpus="6" --memory="8g" --memory-swap="10g" \
  -v ollama:/root/.ollama -p 11434:11434 ollama/ollama
```

### Option C: Just Use Smaller Model (No Docker Changes)
```bash
# Pull smallest model
docker exec -it ollama ollama pull llama3.2:1b

# Use it in your code
request.setModel("llama3.2:1b");
```

This alone will give you 5-10x speed improvement without changing Docker settings!

## Summary

The error happens because swap limit < memory limit. Fix by:
1. Using Docker Desktop GUI (easiest)
2. Updating both memory and swap together
3. Recreating the container with proper limits

**But remember**: Even without increasing resources, switching to `llama3.2:1b` will give you massive speed improvements!
