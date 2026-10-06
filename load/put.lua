counter = 0

request = function()
    counter = counter + 1
    local path = "/v0/entity?id=load-key-" .. counter
    local body = string.rep("x", 100)
    return wrk.format("PUT", path, nil, body)
end