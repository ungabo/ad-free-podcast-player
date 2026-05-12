FROM php:8.3-cli

RUN docker-php-ext-install pdo pdo_sqlite

WORKDIR /app
CMD ["php", "-S", "0.0.0.0:8080", "-t", "public", "router.php"]
