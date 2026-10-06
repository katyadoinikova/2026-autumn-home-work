counter = 0

request = function()
    counter = counter + 1
    local id = ((counter - 1) % 6000) + 1
    local path = "/v0/entity?id=load-key-" .. id
    return wrk.format("GET", path)
end