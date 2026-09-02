FROM node:20-alpine AS builder
WORKDIR /app
COPY package*.json ./
RUN npm i
COPY . .
ARG VITE_API_BASE=/api
ARG VITE_APP_TITLE=Confluence Bot
ENV VITE_API_BASE=$VITE_API_BASE
ENV VITE_APP_TITLE=$VITE_APP_TITLE
RUN npm run build

FROM nginx:1.27-alpine
COPY --from=builder /app/dist /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 80
CMD ["nginx", "-g", "daemon off;"]
