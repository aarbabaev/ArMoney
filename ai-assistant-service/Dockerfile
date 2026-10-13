FROM mcr.microsoft.com/dotnet/sdk:10.0@sha256:e70cdb7f80b0348f5cb85f19a8f670fca061f033d57eed12fa003d58b0e06317 AS build
WORKDIR /source
COPY Assistant.csproj ./
RUN dotnet restore Assistant.csproj
COPY Domain ./Domain
COPY Application ./Application
COPY Adapters ./Adapters
COPY Program.cs ./
COPY migrations ./migrations
RUN dotnet publish Assistant.csproj -c Release --no-restore -o /app
FROM mcr.microsoft.com/dotnet/aspnet:10.0@sha256:222759b391a1aaf241166672c8f99b2d4ada452e7b5319f3c6e8f265a37b5ad4
WORKDIR /app
COPY --from=build /app ./
USER $APP_UID
ENV ASPNETCORE_URLS=http://+:8080
EXPOSE 8080
HEALTHCHECK --interval=15s --timeout=5s --start-period=20s CMD ["dotnet","Assistant.dll","health"]
ENTRYPOINT ["dotnet","Assistant.dll"]
