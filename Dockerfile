# Nginx for "hosting" the package info
FROM nginx:alpine
RUN echo "<h1>Rivery Camera App - Android Version</h1>" > /usr/share/nginx/html/index.html
