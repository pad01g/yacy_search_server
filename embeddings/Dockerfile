# Embedding service for YaCy's vector search (OpenAI-compatible /v1/embeddings, CPU).
# The model is downloaded at build time, so the container needs no network at run time.
FROM python:3.12-slim
RUN pip install --no-cache-dir "fastembed==0.7.4"
WORKDIR /app
COPY server.py .
ENV EMBEDDINGS_CACHE=/models
RUN python server.py --download && chmod -R a+rX /models
USER nobody
EXPOSE 8095
CMD ["python", "server.py"]
