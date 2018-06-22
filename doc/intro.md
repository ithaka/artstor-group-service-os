# Introduction to artstor-group-service

Artstor group service provides CRUD methods for dealing with group of images/objects.

Search for Groups
    GET /api/v1/group Gets all Groups available for the currently logged in user

Create an Image group
    POST /api/v1/group Returns a freshly minted group object
    POST /api/v1/group/{group-id}/copy  Returns a freshly minted group object copied from the provided group id

Deletes a group
    DELETE /api/v1/group/{group-id}

Read a group object
    GET /api/v1/group/{group-id}

Updates a group object
    PUT /api/v1/group/{group-id}
    PUT /api/v1/group/{group-id}/admin/public    For Artstor admin use only: Updates the group's public value.
    PUT /api/v1/group/items/delete    Deletes objects from groups

Gets the metadata for all the specified items the user has access too.
    GET /api/v1/group/{group-id}/metadata Get metadata for group items  (150 max) with group id
    GET /api/v1/group/{group-id}/secure/metadata/{object-id} Accepts object-id in url and returns the metadata in legacy json format for the specified item.
    GET /api/v1/group/{group-id}/items Gets the Items for all the specified items the user has access too. (150 max) with group id

Sharing group using tokens
    POST /api/v1/group/{group-id}/share Creates a token for sharing the specified group
    POST /api/v1/group/redeem/{token} Redeems a token for access to the specified group
    DELETE /api/v1/group/expire/{token} Invalidates a token so it can no longer be used
    GET /api/v1/group/{group-id}/tokens Returns any tokens that are set on the group
